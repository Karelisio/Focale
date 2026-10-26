package app.focale.panorama

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Texture de l'image d'une vidéo 360° : quelle partie de l'image va sur la sphère. Le maillage
 * ([TexturedMesh] : zone de sphère, cube ou maillage du fichier) va de (0, 0) en haut à gauche à
 * (1, 1) en bas à droite de l'image d'un œil ; une texture de `SurfaceTexture` a son origine en bas
 * à gauche, et sa propre transformation (recadrage, retournement) s'applique ensuite.
 */
object VideoFrame {

    /**
     * Matrice 4 × 4 (colonnes à la suite) des coordonnées du maillage vers celles de l'image,
     * avant la transformation de la `SurfaceTexture` : l'image entière, ou celle de l'œil gauche
     * d'une vidéo stéréoscopique (moitié haute, gauche ou droite). Stéréoscopie propre au fichier :
     * le maillage donne déjà ses coordonnées dans l'image entière.
     */
    fun matrix(stereo: StereoMode): FloatArray {
        val scaleX = if (stereo == StereoMode.LEFT_RIGHT || stereo == StereoMode.RIGHT_LEFT) 0.5f else 1f
        val scaleY = if (stereo == StereoMode.TOP_BOTTOM) 0.5f else 1f
        // s = décalage + scaleX · u ; t = 1 - scaleY · v (l'œil gauche en haut : t de 1 à 0,5).
        return Mat4.identity().apply {
            this[0] = scaleX
            this[5] = -scaleY
            this[12] = if (stereo == StereoMode.RIGHT_LEFT) 0.5f else 0f
            this[13] = 1f
        }
    }
}

/**
 * Surface où le lecteur dessine la vidéo ([surface], à lui donner), adossée à la texture externe
 * [textureId] du contexte OpenGL qui l'a créée. Chaque nouvelle image appelle [onFrame] sur le
 * fil principal (à redessiner) ; le fil OpenGL la prend avec [latch]. [release] peut venir de
 * n'importe quel fil : verrouillé avec [latch], il ne laisse jamais lire une texture abandonnée.
 */
internal class VideoTarget(val textureId: Int, onFrame: () -> Unit) {
    private val lock = Any()
    private val pending = AtomicBoolean(false)
    private val texture = SurfaceTexture(textureId)
    private var released = false

    /** Une image au moins est dans la texture : la redessiner ne demande rien de neuf. */
    private var received = false

    val surface = Surface(texture)

    init {
        texture.setOnFrameAvailableListener(
            {
                pending.set(true)
                onFrame()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    /**
     * Fil OpenGL : met la dernière image dans la texture et sa transformation dans [matrix] ;
     * faux si la surface est libérée, ou qu'aucune image n'est encore arrivée.
     */
    fun latch(matrix: FloatArray): Boolean = synchronized(lock) {
        if (released) return false
        val fresh = pending.getAndSet(false)
        if (fresh) texture.updateTexImage()
        if (fresh || received) {
            received = true
            texture.getTransformMatrix(matrix)
            true
        } else {
            false
        }
    }

    fun release() = synchronized(lock) {
        if (released) return
        released = true
        surface.release()
        texture.release()
    }
}

/**
 * Rendu OpenGL ES 2 d'une vidéo 360° : la zone de sphère des photos, le cube ou le maillage du
 * fichier ([mesh], [SphereBuffers]), redressé par [pose] et vu par [camera], texturé par les
 * images de la vidéo, que le lecteur dessine dans une texture externe (`GL_TEXTURE_EXTERNAL_OES`) ;
 * seule l'image de l'œil gauche d'une vidéo stéréoscopique ([VideoFrame]).
 *
 * Chaque contexte OpenGL crée sa surface ([VideoTarget]), passée à [onTarget] depuis le fil
 * OpenGL : le lecteur doit y être rebranché (l'ancienne est perdue avec son contexte). [onFrame] :
 * une image est arrivée, à redessiner (fil principal).
 */
internal class VideoSphereRenderer(
    private val camera: SphereCamera,
    mesh: TexturedMesh,
    pose: SpherePose,
    stereo: StereoMode,
    private val onTarget: (VideoTarget) -> Unit,
    private val onFrame: () -> Unit,
) : GLSurfaceView.Renderer {

    private val sphere = SphereBuffers(mesh)
    private val model = pose.matrix()
    private val frame = VideoFrame.matrix(stereo)
    private val surfaceMatrix = FloatArray(16)

    @Volatile private var target: VideoTarget? = null
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var matrixHandle = 0
    private var texMatrixHandle = 0
    private var samplerHandle = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Nouveau contexte : texture et programme perdus avec le précédent, surface à refaire.
        program = GlPrograms.link(VERTEX_SHADER, FRAGMENT_SHADER, TAG)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Seul mode permis pour une texture externe : la couture d'un tour complet garde un texel net.
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val created = VideoTarget(ids[0], onFrame)
        target = created
        onTarget(created)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val current = target ?: return
        if (program == 0 || !current.latch(surfaceMatrix)) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, Mat4.multiply(camera.viewProjection(), model), 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, Mat4.multiply(surfaceMatrix, frame), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, current.textureId)
        GLES20.glUniform1i(samplerHandle, 0)
        sphere.draw(positionHandle, texCoordHandle)
    }

    /** Fil OpenGL : libère la texture et le programme (la vue se ferme ; la surface l'est par son propriétaire). */
    fun release() {
        target?.let { GLES20.glDeleteTextures(1, intArrayOf(it.textureId), 0) }
        target = null
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    private companion object {
        const val TAG = "VideoSphereRenderer"

        const val VERTEX_SHADER = """
            uniform mat4 uMatrix;
            uniform mat4 uTexMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMatrix * vec4(aPosition, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
