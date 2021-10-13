import java.util.*;
import java.util.logging.Logger;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.streaming.connectors.elasticsearch7.ElasticsearchSink;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;
import org.apache.http.HttpHost;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;

public class KafkaElasticsearch {
    // private static final Logger log = Logger.getLogger(KafkaElasticsearch.class.getSimpleName());
    private static final Logger log = Logger.getLogger(KafkaElasticsearch.class.getSimpleName());

    public static void main(String[] args) throws Exception {
        System.out.println("Starting ....");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Configure kafka parameters
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", "localhost:9092");
        properties.setProperty("zookeeper.connect", "localhost:2181");
        properties.setProperty("group.id", "test-group");
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        // 设置检查点时间间隔
        env.enableCheckpointing(5000);
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers("localhost:9092")
                .setTopics("mytopic")
                .setGroupId("my-group")
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStreamSource<String> kafkaData  = env.fromSource(source,
                WatermarkStrategy.noWatermarks(), "Kafka Source");
        DataStream<String> transSource = kafkaData.map(new MapFunction<String, String>() {
            @Override
            public String map(String s) {
                System.out.println("Received message: " + s);
                return s;
            }
        });

        try {
            List<HttpHost> hosts = new ArrayList<>();
            hosts.add(new HttpHost("localhost", 9200, "http"));
            ElasticsearchSink.Builder<String> builder = new ElasticsearchSink.Builder<>(hosts,
                    new ElasticsearchSinkFunction<String>() {
                        @Override
                        public void process(String s, RuntimeContext runtimeContext, RequestIndexer requestIndexer) {
                            Map<String, String> jsonMap = new HashMap<>();
                            jsonMap.put("message", s);
                            IndexRequest indexRequest = Requests.indexRequest();
                            indexRequest.index("kafka-flink");
                            // indexRequest.id("1000");
                            indexRequest.source(jsonMap);
                            requestIndexer.add(indexRequest);
                        }
                    });

            // Define a sink
            builder.setBulkFlushMaxActions(1);
            transSource.addSink(builder.build());

            // Execute the transform
            env.execute("flink-es");
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
