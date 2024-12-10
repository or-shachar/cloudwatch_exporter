package io.prometheus.cloudwatch;

import static io.prometheus.cloudwatch.CachingDimensionSource.DimensionCacheConfig;

import io.prometheus.client.Collector;
import io.prometheus.client.Collector.Describable;
import io.prometheus.client.Counter;
import io.prometheus.cloudwatch.CachingDimensionSource.DimensionCacheConfig;
import io.prometheus.cloudwatch.DataGetter.MetricRuleData;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClientBuilder;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClient;
import software.amazon.awssdk.services.resourcegroupstaggingapi.ResourceGroupsTaggingApiClientBuilder;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesRequest;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.GetResourcesResponse;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.ResourceTagMapping;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.Tag;
import software.amazon.awssdk.services.resourcegroupstaggingapi.model.TagFilter;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

public class CloudWatchCollector extends Collector implements Describable {
  private static final Logger LOGGER = Logger.getLogger(CloudWatchCollector.class.getName());

  // ARN parsing is based on
  // https://docs.aws.amazon.com/general/latest/gr/aws-arns-and-namespaces.html
  private static final Pattern DEFAULT_ARN_RESOURCE_ID_REGEXP =
      Pattern.compile("(?:([^:/]+)|[^:/]+/([^:]+))$");

  static class ActiveConfig {
    ArrayList<MetricRule> rules;
    CloudWatchAsyncClient cloudWatchClient;
    ResourceGroupsTaggingApiClient taggingClient;
    DimensionSource dimensionSource;

    public ActiveConfig(ActiveConfig cfg) {
      this.rules = new ArrayList<>(cfg.rules);
      this.cloudWatchClient = cfg.cloudWatchClient;
      this.taggingClient = cfg.taggingClient;
      this.dimensionSource = cfg.dimensionSource;
    }

    public ActiveConfig() {}
  }

  static class AWSTagSelect {
    String resourceTypeSelection;
    String resourceIdDimension;
    Map<String, List<String>> tagSelections;
    Pattern arnResourceIdRegexp;
  }

  ActiveConfig activeConfig = new ActiveConfig();

  private static final Counter cloudwatchRequests =
      Counter.build()
          .labelNames("action", "namespace")
          .name("cloudwatch_requests_total")
          .help("API requests made to CloudWatch")
          .register();

  private static final Counter cloudwatchMetricsRequested =
      Counter.build()
          .labelNames("metric_name", "namespace")
          .name("cloudwatch_metrics_requested_total")
          .help("Metrics requested by either GetMetricStatistics or GetMetricData")
          .register();

  private static final Counter taggingApiRequests =
      Counter.build()
          .labelNames("action", "resource_type")
          .name("tagging_api_requests_total")
          .help("API requests made to the Resource Groups Tagging API")
          .register();

  private static final List<String> brokenDynamoMetrics =
      Arrays.asList(
          "ConsumedReadCapacityUnits", "ConsumedWriteCapacityUnits",
          "ProvisionedReadCapacityUnits", "ProvisionedWriteCapacityUnits",
          "ReadThrottleEvents", "WriteThrottleEvents");

  public CloudWatchCollector(Reader in) {
    loadConfig(in, null, null);
  }

  public CloudWatchCollector(String yamlConfig) {
    this(yamlConfig, null, null);
  }

  /* For unittests. */
  protected CloudWatchCollector(
      String jsonConfig,
      CloudWatchAsyncClient cloudWatchClient,
      ResourceGroupsTaggingApiClient taggingClient) {
    this(
        (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(jsonConfig),
        cloudWatchClient,
        taggingClient);
  }

  private CloudWatchCollector(
      Map<String, Object> config,
      CloudWatchAsyncClient cloudWatchClient,
      ResourceGroupsTaggingApiClient taggingClient) {
    loadConfig(config, cloudWatchClient, taggingClient);
  }

  @Override
  public List<MetricFamilySamples> describe() {
    return Collections.emptyList();
  }

  protected void reloadConfig() throws IOException {
    LOGGER.log(Level.INFO, "Reloading configuration");
    try (FileReader reader = new FileReader(WebServer.configFilePath); ) {
      loadConfig(reader, activeConfig.cloudWatchClient, activeConfig.taggingClient);
    }
  }

  protected void loadConfig(
      Reader in,
      CloudWatchAsyncClient cloudWatchClient,
      ResourceGroupsTaggingApiClient taggingClient) {
    loadConfig(
        (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(in),
        cloudWatchClient,
        taggingClient);
  }

  private void loadConfig(
      Map<String, Object> config,
      CloudWatchAsyncClient cloudWatchClient,
      ResourceGroupsTaggingApiClient taggingClient) {
    if (config == null) { // Yaml config empty, set config to empty map.
      config = new HashMap<>();
    }

    int defaultPeriod = 60;
    if (config.containsKey("period_seconds")) {
      defaultPeriod = ((Number) config.get("period_seconds")).intValue();
    }
    int defaultRange = 600;
    if (config.containsKey("range_seconds")) {
      defaultRange = ((Number) config.get("range_seconds")).intValue();
    }
    int defaultDelay = 600;
    if (config.containsKey("delay_seconds")) {
      defaultDelay = ((Number) config.get("delay_seconds")).intValue();
    }

    boolean defaultCloudwatchTimestamp = true;
    if (config.containsKey("set_timestamp")) {
      defaultCloudwatchTimestamp = (Boolean) config.get("set_timestamp");
    }

    boolean defaultUseGetMetricData = false;
    if (config.containsKey("use_get_metric_data")) {
      defaultUseGetMetricData = (Boolean) config.get("use_get_metric_data");
    }

    Duration defaultMetricCacheSeconds = Duration.ofSeconds(0);
    if (config.containsKey("list_metrics_cache_ttl")) {
      defaultMetricCacheSeconds =
          Duration.ofSeconds(((Number) config.get("list_metrics_cache_ttl")).intValue());
    }

    boolean defaultWarnOnMissingDimensions = false;
    if (config.containsKey("warn_on_empty_list_dimensions")) {
      defaultWarnOnMissingDimensions = (Boolean) config.get("warn_on_empty_list_dimensions");
    }

    String region = (String) config.get("region");

    if (cloudWatchClient == null) {
      CloudWatchAsyncClientBuilder clientBuilder = CloudWatchAsyncClient.builder();

      if (config.containsKey("role_arn")) {
        clientBuilder.credentialsProvider(getRoleCredentialProvider(config));
      }

      if (config.containsKey("parallelism")) {
        ExecutorService threadPool =
            Executors.newFixedThreadPool(((Number) config.get("parallelism")).intValue());
        clientBuilder.asyncConfiguration(
            b ->
                b.advancedOption(
                    SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, threadPool));
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(
                    () -> {
                      threadPool.shutdown();
                    }));
      }

      if (region != null) {
        clientBuilder.region(Region.of(region));
      }

      cloudWatchClient = clientBuilder.build();
    }

    if (taggingClient == null) {
      ResourceGroupsTaggingApiClientBuilder clientBuilder =
          ResourceGroupsTaggingApiClient.builder();

      if (config.containsKey("role_arn")) {
        clientBuilder.credentialsProvider(getRoleCredentialProvider(config));
      }
      if (region != null) {
        clientBuilder.region(Region.of(region));
      }
      taggingClient = clientBuilder.build();
    }

    if (!config.containsKey("metrics")) {
      throw new IllegalArgumentException("Must provide metrics");
    }

    DimensionCacheConfig metricCacheConfig = new DimensionCacheConfig(defaultMetricCacheSeconds);
    ArrayList<MetricRule> rules = new ArrayList<>();

    for (Object ruleObject : (List<Map<String, Object>>) config.get("metrics")) {
      Map<String, Object> yamlMetricRule = (Map<String, Object>) ruleObject;
      MetricRule rule = new MetricRule();
      rules.add(rule);
      if (!yamlMetricRule.containsKey("aws_namespace")
          || !yamlMetricRule.containsKey("aws_metric_name")) {
        throw new IllegalArgumentException("Must provide aws_namespace and aws_metric_name");
      }
      rule.awsNamespace = (String) yamlMetricRule.get("aws_namespace");
      rule.awsMetricName = (String) yamlMetricRule.get("aws_metric_name");
      if (yamlMetricRule.containsKey("help")) {
        rule.help = (String) yamlMetricRule.get("help");
      }
      if (yamlMetricRule.containsKey("aws_dimensions")) {
        rule.awsDimensions = (List<String>) yamlMetricRule.get("aws_dimensions");
      }
      if (yamlMetricRule.containsKey("aws_dimension_select")
          && yamlMetricRule.containsKey("aws_dimension_select_regex")) {
        throw new IllegalArgumentException(
            "Must not provide aws_dimension_select and aws_dimension_select_regex at the same time");
      }
      if (yamlMetricRule.containsKey("aws_dimension_select")) {
        rule.awsDimensionSelect =
            (Map<String, List<String>>) yamlMetricRule.get("aws_dimension_select");
      }
      if (yamlMetricRule.containsKey("aws_dimension_select_regex")) {
        rule.awsDimensionSelectRegex =
            (Map<String, List<String>>) yamlMetricRule.get("aws_dimension_select_regex");
      }
      if (yamlMetricRule.containsKey("aws_statistics")) {
        rule.awsStatistics = new ArrayList<>();
        for (String statistic : (List<String>) yamlMetricRule.get("aws_statistics")) {
          rule.awsStatistics.add(Statistic.fromValue(statistic));
        }
      } else if (!yamlMetricRule.containsKey("aws_extended_statistics")) {
        rule.awsStatistics = new ArrayList<>();
        for (String statistic :
            Arrays.asList("Sum", "SampleCount", "Minimum", "Maximum", "Average")) {
          rule.awsStatistics.add(Statistic.fromValue(statistic));
        }
      }
      if (yamlMetricRule.containsKey("aws_extended_statistics")) {
        rule.awsExtendedStatistics = (List<String>) yamlMetricRule.get("aws_extended_statistics");
      }
      if (yamlMetricRule.containsKey("period_seconds")) {
        rule.periodSeconds = ((Number) yamlMetricRule.get("period_seconds")).intValue();
      } else {
        rule.periodSeconds = defaultPeriod;
      }
      if (yamlMetricRule.containsKey("range_seconds")) {
        rule.rangeSeconds = ((Number) yamlMetricRule.get("range_seconds")).intValue();
      } else {
        rule.rangeSeconds = defaultRange;
      }
      if (yamlMetricRule.containsKey("delay_seconds")) {
        rule.delaySeconds = ((Number) yamlMetricRule.get("delay_seconds")).intValue();
      } else {
        rule.delaySeconds = defaultDelay;
      }
      if (yamlMetricRule.containsKey("set_timestamp")) {
        rule.cloudwatchTimestamp = (Boolean) yamlMetricRule.get("set_timestamp");
      } else {
        rule.cloudwatchTimestamp = defaultCloudwatchTimestamp;
      }
      if (yamlMetricRule.containsKey("use_get_metric_data")) {
        rule.useGetMetricData = (Boolean) yamlMetricRule.get("use_get_metric_data");
      } else {
        rule.useGetMetricData = defaultUseGetMetricData;
      }
      if (yamlMetricRule.containsKey("warn_on_empty_list_dimensions")) {
        rule.warnOnEmptyListDimensions =
            (Boolean) yamlMetricRule.get("warn_on_empty_list_dimensions");
      } else {
        rule.warnOnEmptyListDimensions = defaultWarnOnMissingDimensions;
      }

      if (yamlMetricRule.containsKey("aws_tag_select")) {
        Map<String, Object> yamlAwsTagSelect =
            (Map<String, Object>) yamlMetricRule.get("aws_tag_select");
        if (!yamlAwsTagSelect.containsKey("resource_type_selection")
            || !yamlAwsTagSelect.containsKey("resource_id_dimension")) {
          throw new IllegalArgumentException(
              "Must provide resource_type_selection and resource_id_dimension");
        }
        AWSTagSelect awsTagSelect = new AWSTagSelect();
        rule.awsTagSelect = awsTagSelect;

        awsTagSelect.resourceTypeSelection =
            (String) yamlAwsTagSelect.get("resource_type_selection");
        awsTagSelect.resourceIdDimension = (String) yamlAwsTagSelect.get("resource_id_dimension");

        if (yamlAwsTagSelect.containsKey("tag_selections")) {
          awsTagSelect.tagSelections =
              (Map<String, List<String>>) yamlAwsTagSelect.get("tag_selections");
        }
        if (yamlAwsTagSelect.containsKey("arn_resource_id_regexp")) {
          awsTagSelect.arnResourceIdRegexp =
              Pattern.compile((String) yamlAwsTagSelect.get("arn_resource_id_regexp"));
        }
      }

      if (yamlMetricRule.containsKey("list_metrics_cache_ttl")) {
        rule.listMetricsCacheTtl =
            Duration.ofSeconds(((Number) yamlMetricRule.get("list_metrics_cache_ttl")).intValue());
        metricCacheConfig.addOverride(rule);
      } else {
        rule.listMetricsCacheTtl = defaultMetricCacheSeconds;
      }
    }

    DimensionSource dimensionSource =
        new DefaultDimensionSource(cloudWatchClient, cloudwatchRequests);
    if (defaultMetricCacheSeconds.toSeconds() > 0 || !metricCacheConfig.metricConfig.isEmpty()) {
      dimensionSource = new CachingDimensionSource(dimensionSource, metricCacheConfig);
    }

    loadConfig(rules, cloudWatchClient, taggingClient, dimensionSource);
  }

  private void loadConfig(
      ArrayList<MetricRule> rules,
      CloudWatchAsyncClient cloudWatchClient,
      ResourceGroupsTaggingApiClient taggingClient,
      DimensionSource dimensionSource) {
    synchronized (activeConfig) {
      activeConfig.cloudWatchClient = cloudWatchClient;
      activeConfig.taggingClient = taggingClient;
      activeConfig.rules = rules;
      activeConfig.dimensionSource = dimensionSource;
    }
  }

  private AwsCredentialsProvider getRoleCredentialProvider(Map<String, Object> config) {
    StsClient stsClient =
        StsClient.builder().region(Region.of((String) config.get("region"))).build();
    AssumeRoleRequest assumeRoleRequest =
        AssumeRoleRequest.builder()
            .roleArn((String) config.get("role_arn"))
            .roleSessionName("cloudwatch_exporter")
            .build();
    return StsAssumeRoleCredentialsProvider.builder()
        .stsClient(stsClient)
        .refreshRequest(assumeRoleRequest)
        .build();
  }

  private List<ResourceTagMapping> getResourceTagMappings(
      MetricRule rule, ResourceGroupsTaggingApiClient taggingClient) {
    if (rule.awsTagSelect == null) {
      return Collections.emptyList();
    }

    List<TagFilter> tagFilters = new ArrayList<>();
    if (rule.awsTagSelect.tagSelections != null) {
      for (Entry<String, List<String>> entry : rule.awsTagSelect.tagSelections.entrySet()) {
        tagFilters.add(TagFilter.builder().key(entry.getKey()).values(entry.getValue()).build());
      }
    }

    List<ResourceTagMapping> resourceTagMappings = new ArrayList<>();
    GetResourcesRequest.Builder requestBuilder =
        GetResourcesRequest.builder()
            .tagFilters(tagFilters)
            .resourceTypeFilters(rule.awsTagSelect.resourceTypeSelection);
    String paginationToken = "";
    do {
      requestBuilder.paginationToken(paginationToken);

      GetResourcesResponse response = taggingClient.getResources(requestBuilder.build());
      taggingApiRequests.labels("getResources", rule.awsTagSelect.resourceTypeSelection).inc();

      resourceTagMappings.addAll(response.resourceTagMappingList());

      paginationToken = response.paginationToken();
    } while (paginationToken != null && !paginationToken.isEmpty());

    return resourceTagMappings;
  }

  private List<String> extractResourceIds(
      Pattern arnResourceIdRegexp, List<ResourceTagMapping> resourceTagMappings) {
    List<String> resourceIds = new ArrayList<>();
    for (ResourceTagMapping resourceTagMapping : resourceTagMappings) {
      resourceIds.add(
          extractResourceIdFromArn(resourceTagMapping.resourceARN(), arnResourceIdRegexp));
    }
    return resourceIds;
  }

  private static Pattern getArnResourceIdRegexp(MetricRule rule) {
    if (rule.awsTagSelect != null && rule.awsTagSelect.arnResourceIdRegexp != null) {
      return rule.awsTagSelect.arnResourceIdRegexp;
    }
    return DEFAULT_ARN_RESOURCE_ID_REGEXP;
  }

  private String toSnakeCase(String str) {
    return str.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
  }

  private String safeName(String s) {
    // Change invalid chars to underscore, and merge underscores.
    return s.replaceAll("[^a-zA-Z0-9:_]", "_").replaceAll("__+", "_");
  }

  private String safeLabelName(String s) {
    // Change invalid chars to underscore, and merge underscores.
    return s.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("__+", "_");
  }

  private String help(MetricRule rule, String unit, String statistic) {
    if (rule.help != null) {
      return rule.help;
    }
    return "CloudWatch metric "
        + rule.awsNamespace
        + " "
        + rule.awsMetricName
        + " Dimensions: "
        + rule.awsDimensions
        + " Statistic: "
        + statistic
        + " Unit: "
        + unit;
  }

  private String sampleLabelSuffixBy(Statistic s) {
    switch (s) {
      case SUM:
        return "_sum";
      case SAMPLE_COUNT:
        return "_sample_count";
      case MINIMUM:
        return "_minimum";
      case MAXIMUM:
        return "_maximum";
      case AVERAGE:
        return "_average";
      default:
        throw new RuntimeException("I did not expect this stats!");
    }
  }

  private void scrape(List<MetricFamilySamples> mfs) {
    ActiveConfig config = new ActiveConfig(activeConfig);
    Set<String> publishedResourceInfo = new HashSet<>();

    long start = System.currentTimeMillis();
    List<MetricFamilySamples.Sample> infoSamples = new ArrayList<>();

    for (MetricRule rule : config.rules) {
      String baseName =
          safeName(rule.awsNamespace.toLowerCase() + "_" + toSnakeCase(rule.awsMetricName));
      String jobName = safeName(rule.awsNamespace.toLowerCase());
      Map<Statistic, List<MetricFamilySamples.Sample>> baseSamples = new HashMap<>();
      for (Statistic s : Statistic.values()) {
        baseSamples.put(s, new ArrayList<>());
      }
      HashMap<String, List<MetricFamilySamples.Sample>> extendedSamples = new HashMap<>();

      String unit = null;

      if (rule.awsNamespace.equals("AWS/DynamoDB")
          && rule.awsDimensions != null
          && rule.awsDimensions.contains("GlobalSecondaryIndexName")
          && brokenDynamoMetrics.contains(rule.awsMetricName)) {
        baseName += "_index";
      }

      List<ResourceTagMapping> resourceTagMappings =
          getResourceTagMappings(rule, config.taggingClient);
      Pattern arnResourceIdRegexp = getArnResourceIdRegexp(rule);
      List<String> tagBasedResourceIds =
          extractResourceIds(arnResourceIdRegexp, resourceTagMappings);

      List<List<Dimension>> dimensionList =
          config.dimensionSource.getDimensions(rule, tagBasedResourceIds).getDimensions();
      DataGetter dataGetter = null;
      if (rule.useGetMetricData) {
        dataGetter =
            new GetMetricDataDataGetter(
                config.cloudWatchClient,
                start,
                rule,
                cloudwatchRequests,
                cloudwatchMetricsRequested,
                dimensionList);
      } else {
        dataGetter =
            new GetMetricStatisticsDataGetter(
                config.cloudWatchClient,
                start,
                rule,
                cloudwatchRequests,
                cloudwatchMetricsRequested);
      }

      for (List<Dimension> dimensions : dimensionList) {
        MetricRuleData values = dataGetter.metricRuleDataFor(dimensions);
        if (values == null) {
          continue;
        }
        unit = values.unit;
        List<String> labelNames = new ArrayList<>();
        List<String> labelValues = new ArrayList<>();
        labelNames.add("job");
        labelValues.add(jobName);
        labelNames.add("instance");
        labelValues.add("");
        for (Dimension d : dimensions) {
          labelNames.add(safeLabelName(toSnakeCase(d.name())));
          labelValues.add(d.value());
        }

        Long timestamp = null;
        if (rule.cloudwatchTimestamp) {
          timestamp = values.timestamp.toEpochMilli();
        }

        // iterate over aws statistics
        for (Entry<Statistic, Double> e : values.statisticValues.entrySet()) {
          String suffix = sampleLabelSuffixBy(e.getKey());
          baseSamples
              .get(e.getKey())
              .add(
                  new MetricFamilySamples.Sample(
                      baseName + suffix, labelNames, labelValues, e.getValue(), timestamp));
        }

        // iterate over extended values
        for (Entry<String, Double> entry : values.extendedValues.entrySet()) {
          List<MetricFamilySamples.Sample> samples =
              extendedSamples.getOrDefault(entry.getKey(), new ArrayList<>());
          samples.add(
              new MetricFamilySamples.Sample(
                  baseName + "_" + safeName(toSnakeCase(entry.getKey())),
                  labelNames,
                  labelValues,
                  entry.getValue(),
                  timestamp));
          extendedSamples.put(entry.getKey(), samples);
        }
      }

      if (!baseSamples.get(Statistic.SUM).isEmpty()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_sum",
                Type.GAUGE,
                help(rule, unit, "Sum"),
                baseSamples.get(Statistic.SUM)));
      }
      if (!baseSamples.get(Statistic.SAMPLE_COUNT).isEmpty()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_sample_count",
                Type.GAUGE,
                help(rule, unit, "SampleCount"),
                baseSamples.get(Statistic.SAMPLE_COUNT)));
      }
      if (!baseSamples.get(Statistic.MINIMUM).isEmpty()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_minimum",
                Type.GAUGE,
                help(rule, unit, "Minimum"),
                baseSamples.get(Statistic.MINIMUM)));
      }
      if (!baseSamples.get(Statistic.MAXIMUM).isEmpty()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_maximum",
                Type.GAUGE,
                help(rule, unit, "Maximum"),
                baseSamples.get(Statistic.MAXIMUM)));
      }
      if (!baseSamples.get(Statistic.AVERAGE).isEmpty()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_average",
                Type.GAUGE,
                help(rule, unit, "Average"),
                baseSamples.get(Statistic.AVERAGE)));
      }
      for (Entry<String, List<MetricFamilySamples.Sample>> entry : extendedSamples.entrySet()) {
        mfs.add(
            new MetricFamilySamples(
                baseName + "_" + safeName(toSnakeCase(entry.getKey())),
                Type.GAUGE,
                help(rule, unit, entry.getKey()),
                entry.getValue()));
      }

      // Add the "aws_resource_info" metric for existing tag mappings
      for (ResourceTagMapping resourceTagMapping : resourceTagMappings) {
        if (!publishedResourceInfo.contains(resourceTagMapping.resourceARN())) {
          List<String> labelNames = new ArrayList<>();
          List<String> labelValues = new ArrayList<>();
          labelNames.add("job");
          labelValues.add(jobName);
          labelNames.add("instance");
          labelValues.add("");
          labelNames.add("arn");
          labelValues.add(resourceTagMapping.resourceARN());
          labelNames.add(safeLabelName(toSnakeCase(rule.awsTagSelect.resourceIdDimension)));
          labelValues.add(
              extractResourceIdFromArn(resourceTagMapping.resourceARN(), arnResourceIdRegexp));
          for (Tag tag : resourceTagMapping.tags()) {
            // Avoid potential collision between resource tags and other metric labels by adding the
            // "tag_" prefix
            // The AWS tags are case sensitive, so to avoid loosing information and label
            // collisions, tag keys are not snaked cased
            labelNames.add("tag_" + safeLabelName(tag.key()));
            labelValues.add(tag.value());
          }

          infoSamples.add(
              new MetricFamilySamples.Sample("aws_resource_info", labelNames, labelValues, 1));

          publishedResourceInfo.add(resourceTagMapping.resourceARN());
        }
      }
    }
    mfs.add(
        new MetricFamilySamples(
            "aws_resource_info",
            Type.GAUGE,
            "AWS information available for resource",
            infoSamples));
  }

  public List<MetricFamilySamples> collect() {
    long start = System.nanoTime();
    double error = 0;
    List<MetricFamilySamples> mfs = new ArrayList<>();
    try {
      scrape(mfs);
    } catch (Exception e) {
      error = 1;
      LOGGER.log(Level.WARNING, "CloudWatch scrape failed", e);
    }
    List<MetricFamilySamples.Sample> samples = new ArrayList<>();
    samples.add(
        new MetricFamilySamples.Sample(
            "cloudwatch_exporter_scrape_duration_seconds",
            new ArrayList<>(),
            new ArrayList<>(),
            (System.nanoTime() - start) / 1.0E9));
    mfs.add(
        new MetricFamilySamples(
            "cloudwatch_exporter_scrape_duration_seconds",
            Type.GAUGE,
            "Time this CloudWatch scrape took, in seconds.",
            samples));

    samples = new ArrayList<>();
    samples.add(
        new MetricFamilySamples.Sample(
            "cloudwatch_exporter_scrape_error", new ArrayList<>(), new ArrayList<>(), error));
    mfs.add(
        new MetricFamilySamples(
            "cloudwatch_exporter_scrape_error",
            Type.GAUGE,
            "Non-zero if this scrape failed.",
            samples));
    return mfs;
  }

  private String extractResourceIdFromArn(String arn, Pattern arnResourceIdRegexp) {
    final Matcher matcher = arnResourceIdRegexp.matcher(arn);
    if (matcher.find()) {
      for (int i = 1; i <= matcher.groupCount(); i++) {
        final String group = matcher.group(i);
        if (group != null && !group.isEmpty()) {
          return group;
        }
      }
    }
    return "";
  }

  /** Convenience function to run standalone. */
  public static void main(String[] args) {
    String region = "eu-west-1";
    if (args.length > 0) {
      region = args[0];
    }
    new BuildInfoCollector().register();
    CloudWatchCollector jc =
        new CloudWatchCollector(
            ("{"
                    + "`region`: `"
                    + region
                    + "`,"
                    + "`metrics`: [{`aws_namespace`: `AWS/ELB`, `aws_metric_name`: `RequestCount`, `aws_dimensions`: [`AvailabilityZone`, `LoadBalancerName`]}] ,"
                    + "}")
                .replace('`', '"'));
    for (MetricFamilySamples mfs : jc.collect()) {
      System.out.println(mfs);
    }
  }
}
