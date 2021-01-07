package cz.csas.bs.stream;

import cz.csas.avroschemas.BuildingSavingsCase_update.v1.BuildingSavingsCase_update;
import cz.csas.avroschemas.BuildingSavingsModelation_changed.v1.BuildingSavingsModelation_changed;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.quarkus.kafka.streams.runtime.KafkaStreamsRuntimeConfig;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Produced;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.util.Collections;
import java.util.Map;

@ApplicationScoped
public class CaseUpdateTolology {

    private static final Logger LOG = Logger.getLogger(CaseUpdateTolology.class);

    @ConfigProperty(name = "bs.topic.case-update")
    String topicCaseUpdate;
    @ConfigProperty(name = "bs.topic.modelation-changed")
    String topicModelationChanged;

    final Map<String, String> serdeConfig;

    @Inject
    @SuppressWarnings("CdiInjectionPointsInspection")
    public CaseUpdateTolology(KafkaStreamsRuntimeConfig runtimeConfig) {
        serdeConfig = runtimeConfig.schemaRegistryUrl.map(s -> Collections.singletonMap("schema.registry.url", s)).orElse(Collections.emptyMap());
    }

    @Produces
    public Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        builder
                .stream(topicModelationChanged, Consumed.with(Serdes.String(), avroValueSerde(BuildingSavingsModelation_changed.class)))
                .map((key, value) -> new KeyValue<>(value.getId().toString(), BuildingSavingsCase_update.newBuilder()
                        .setCaseId(value.getId())
                        .setCluid(value.getCluid())
                        .setModificationTime(value.getDate().toEpochMilli()) // TODO coalesce
                        .setProcessingPhase("BS_DRAFT") // TODO constant
                        .build())
                )
                .peek((key, value) -> LOG.debugv("Sending {0} to {1}", key, topicCaseUpdate))
                .to(topicCaseUpdate, Produced.with(Serdes.String(), avroValueSerde(BuildingSavingsCase_update.class)));

        return builder.build();
    }

    private <T extends SpecificRecord> Serde<T> avroValueSerde(Class<T> clazz) {
        final Serde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
