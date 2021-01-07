package cz.csas.bs.stream;

import cz.csas.avroschemas.BuildingSavingsApplication_clientDataChanged.v1.BuildingSavingsApplication_clientDataChanged;
import cz.csas.avroschemas.BuildingSavingsCase_clientValidated.v1.BuildingSavingsCase_clientValidated;
import cz.csas.avroschemas.BuildingSavingsModelation_changed.v1.BuildingSavingsModelation_changed;
import cz.csas.avroschemas.buildingsavingscase.v03_01.BuildingSavingsCase;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import io.quarkus.kafka.streams.runtime.KafkaStreamsRuntimeConfig;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
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
public class BuildingsSavingsCaseTopology {

    private static final Logger LOG = Logger.getLogger(BuildingsSavingsCaseTopology.class);

    @ConfigProperty(name = "bs.topic.case")
    String topicCase;
    @ConfigProperty(name = "bs.topic.modelation-changed")
    String topicModelationChanged;
    @ConfigProperty(name = "bs.topic.client-data-changed")
    String topicClientDataChanged;
    @ConfigProperty(name = "bs.topic.client-validated")
    String topicClientValidated;

    final Map<String, String> serdeConfig;

    @Inject
    @SuppressWarnings("CdiInjectionPointsInspection")
    public BuildingsSavingsCaseTopology(KafkaStreamsRuntimeConfig runtimeConfig) {
        serdeConfig = runtimeConfig.schemaRegistryUrl.map(s -> Collections.singletonMap("schema.registry.url", s)).orElse(Collections.emptyMap());
    }

    @Produces
    public Topology buildTopology() {
        StreamsBuilder builder = new StreamsBuilder();

        var modelationChanged = builder
                .stream(topicModelationChanged, Consumed.with(Serdes.String(), avroValueSerde(BuildingSavingsModelation_changed.class)))
                .selectKey((key, value) -> value.getId().toString()) // Repartition by caseId
                .toTable();

        var clientDataChanged = builder
                .stream(topicClientDataChanged, Consumed.with(Serdes.String(), avroValueSerde(BuildingSavingsApplication_clientDataChanged.class)))
                .selectKey((key, value) -> value.getCaseId().toString()) // Repartition by caseId
                .toTable();

        var clientValidated = builder.globalTable(topicClientValidated, Consumed.with(Serdes.String(), avroValueSerde(BuildingSavingsCase_clientValidated.class)));

        modelationChanged
                .outerJoin(clientDataChanged, (modelation, clientData) -> {
                            var b = BuildingSavingsCase.newBuilder()
                                    .setCaseId(modelation.getId());
                            b.getClientsBuilder().getApplicantBuilder().setCluid(modelation.getCluid());
                            // TODO ...
                            return b.build();
                        }
                )
                .toStream()
                // Repartition by cluid for join with clientValidated
                // NOTE since it is global table with persistent store, we can probably just get the data right away
                .selectKey((key, value) -> value.getClients().getApplicant().getCluid().toString())
                .leftJoin(clientValidated, (key, value) -> key, (bsCase, validated) -> {
                    var b = BuildingSavingsCase.newBuilder(bsCase);
                    b.getClientsBuilder().getApplicantBuilder().getClientValidationBuilder()
                            .setIsValid(validated.getIsValid());
                    // .setErrors(validated.getErrors()); // TODO map errors
                    return b.build();
                })
                .selectKey((key, value) -> value.getCaseId().toString())
                .to(topicCase, Produced.with(Serdes.String(), avroValueSerde(BuildingSavingsCase.class)));


        return builder.build();
    }

    private <T extends SpecificRecord> Serde<T> avroValueSerde(Class<T> clazz) {
        final Serde<T> serde = new SpecificAvroSerde<>();
        serde.configure(serdeConfig, false);
        return serde;
    }
}
