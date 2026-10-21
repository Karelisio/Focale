package app.focale.people

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * SFace (OpenCV Zoo, licence Apache 2.0, version quantifiée int8) exécuté par ONNX
 * Runtime : visage aligné 112 × 112 → empreinte de 128 valeurs, ramenée à la norme 1.
 * Sans dépendance à Android : les tests unitaires l'exécutent sur la JVM.
 */
class SFaceModel(model: ByteArray, threads: Int = DEFAULT_THREADS) : AutoCloseable {

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // Aucune télémétrie : la version Android d'ONNX Runtime en embarque une, dont le client
        // d'envoi n'est pas créé (fournisseur retiré du manifeste) ; coupée en plus ici.
        runCatching { environment.setTelemetry(false) }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            // Le modèle déclare ses poids en entrées du graphe : avertissements sans intérêt.
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            environment.createSession(model, options)
        }
    }

    /** Empreinte normalisée de [input] (voir [FaceAlignment.tensor]). */
    fun embed(input: FloatArray): FloatArray {
        require(input.size == INPUT_SIZE) { "Entrée de ${input.size} valeurs, $INPUT_SIZE attendues" }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), SHAPE).use { tensor ->
            session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = (result[0].value as Array<FloatArray>)[0]
                return FaceEmbeddings.normalize(output)
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /** Modèle embarqué : `app/src/main/assets/models/`. */
        const val ASSET = "models/sface_int8.onnx"
        const val INPUT_NAME = "data"
        const val INPUT_SIZE = 3 * FaceAlignment.SIZE * FaceAlignment.SIZE
        private val SHAPE = longArrayOf(1, 3, FaceAlignment.SIZE.toLong(), FaceAlignment.SIZE.toLong())
        private const val DEFAULT_THREADS = 2
    }
}
