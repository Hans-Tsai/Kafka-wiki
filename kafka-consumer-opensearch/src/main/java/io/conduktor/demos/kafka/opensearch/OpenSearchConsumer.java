package opensearch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

public class OpenSearchConsumer {

    public static RestHighLevelClient createOpenSearchClient() {
        String connString = "http://localhost:9200";
        RestHighLevelClient restHighLevelClient;
        URI connUri = URI.create(connString);
        String userInfo = connUri.getUserInfo();

        if (userInfo == null) {
            restHighLevelClient = new RestHighLevelClient(RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort(), "http")));
        } else {
            String[] auth = userInfo.split(":");
            CredentialsProvider cp = new BasicCredentialsProvider();
            cp.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(auth[0], auth[1]));

            restHighLevelClient = new RestHighLevelClient(
                RestClient.builder(new HttpHost(connUri.getHost(), connUri.getPort(), connUri.getScheme()))
                    .setHttpClientConfigCallback(
                        httpAsyncClientBuilder -> httpAsyncClientBuilder.setDefaultCredentialsProvider(cp)
                            .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())));
        }

        return restHighLevelClient;
    }

    private static KafkaConsumer<String, String> createKafkaConsumer(){
        String groupId = "consumer-opensearch-demo";
        String bootstrapServers = "127.0.0.1:9092";

        Properties properties = new Properties();
        properties.setProperty(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new KafkaConsumer<>(properties);
    }

    private static String extractId(String json) {
        try {
            JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
            JsonElement metaElement = jsonObject.get("meta");

            if (metaElement != null && metaElement.isJsonObject()) {
                JsonElement idElement = metaElement.getAsJsonObject().get("id");

                if (idElement != null) {
                    return idElement.getAsString();
                } else {
                    return "missing-id";
                }
            } else {
                return "missing-meta";
            }
        } catch (Exception e) {
            return "parse-error";
        }
    }

    public static void main(String[] args) throws IOException {
        Logger log = LoggerFactory.getLogger(OpenSearchConsumer.class.getSimpleName());
        RestHighLevelClient openSearchClient = createOpenSearchClient();
        KafkaConsumer<String, String> consumer = createKafkaConsumer();
        final Thread mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                log.info("Detected a shutdown, let's exit by calling consumer.wakeup()...");
                consumer.wakeup();

                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        try(openSearchClient; consumer){
            String[] topics = {"wikimedia_stats_website", "wikimedia_stats_timeseries", "wikimedia_stats_bots", "wikimedia"};
            for (String index : topics) {
                boolean indexExists = openSearchClient.indices().exists(new GetIndexRequest(index), RequestOptions.DEFAULT);
                if (!indexExists){
                    CreateIndexRequest createIndexRequest = new CreateIndexRequest(index);
                    openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                    log.info("The " + index + " Index has been created!");
                } else {
                    log.info("The " + index + " Index already exists");
                }
            }

            consumer.subscribe(Arrays.asList("wikimedia.stats.website", "wikimedia.stats.timeseries", "wikimedia.stats.bots", "wikimedia.recentchange"));

            while(true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(3000));

                int recordCount = records.count();
                log.info("Received " + recordCount + " record(s)");

                BulkRequest bulkRequest = new BulkRequest();

                for (ConsumerRecord<String, String> record : records) {
                    try {
                        String topic = record.topic();
                        String id = extractId(record.value());
                        String indexName;

                        switch (topic) {
                            case "wikimedia.stats.website":
                                indexName = "wikimedia_stats_website";
                                break;
                            case "wikimedia.stats.timeseries":
                                indexName = "wikimedia_stats_timeseries";
                                break;
                            case "wikimedia.stats.bots":
                                indexName = "wikimedia_stats_bots";
                                break;
                            case "wikimedia.recentchange":
                                indexName = "wikimedia";
                                break;
                            default:
                                throw new IllegalStateException("Unexpected value: " + topic);
                        }

                        IndexRequest indexRequest = new IndexRequest(indexName)
                            .source(record.value(), XContentType.JSON)
                            .id(id);

                        bulkRequest.add(indexRequest);
                    } catch (Exception e){
                        log.error("Error processing record", e);
                    }
                }

                if (bulkRequest.numberOfActions() > 0){
                    BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                    log.info("Inserted " + bulkResponse.getItems().length + " record(s).");

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    consumer.commitSync();
                    log.info("Offsets have been committed!");
                }
            }
        } catch (WakeupException e) {
            log.info("Consumer is starting to shut down");
        } catch (Exception e) {
            log.error("Unexpected exception in the consumer", e);
        } finally {
            consumer.close();
            openSearchClient.close();
            log.info("The consumer is now gracefully shut down");
        }
    }
}