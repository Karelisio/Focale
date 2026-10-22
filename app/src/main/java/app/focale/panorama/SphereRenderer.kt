package app.focale.panorama

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Rendu OpenGL ES 2 d'une photo 360° : la zone couverte de la sphère ([SphereMesh]) texturée
 * par la photo, vue depuis son centre par [camera] ; le reste est noir.
 *
 * La texture arrive par [setTexture], depuis le fil OpenGL (`queueEvent`) : la photo est
 * décodée ailleurs, à la taille que permet [onContext] (`GL_MAX_TEXTURE_SIZE`, appelé à chaque
 * nouveau contexte, dont la texture est alors à recharger). Le bitmap est libéré dès qu'il est
 * envoyé ; [release] libère la texture et le programme.
 */
internal class SphereRenderer(
    private val camera: SphereCamera,
    area: SphereArea,
    private val onContext: (maxTextureSize: Int) -> Unit,
) : GLSurfaceView.Renderer {

    private val sphere = SphereBuffers(area)
    private val wraps = area.wrapsAround

    private var program = 0
    private var texture = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var matrixHandle = 0
    private var samplerHandle = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Nouveau contexte : anciens objets perdus avec le précédent.
        texture = 0
        program = GlPrograms.link(VERTEX_SHADER, FRAGMENT_SHADER, TAG)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMatrix")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        onContext(max[0].takeIf { it > 0 } ?: MIN_TEXTURE_SIZE)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (texture == 0 || program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, camera.viewProjection(), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(samplerHandle, 0)
        sphere.draw(positionHandle, texCoordHandle)
    }

    /** Fil OpenGL : envoie [bitmap] (puissances de deux) en texture avec ses mipmaps, puis le libère. */
    fun setTexture(bitmap: Bitmap) {
        try {
            deleteTexture()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            // Tour complet : le filtrage enjambe la couture plutôt que de s'arrêter au bord.
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, if (wraps) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        } finally {
            bitmap.recycle()
        }
    }

    /** Fil OpenGL : libère la texture et le programme (la vue se ferme). */
    fun release() {
        deleteTexture()
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
    }

    private fun deleteTexture() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
    }

    private companion object {
        const val TAG = "SphereRenderer"

        /** Minimum garanti par OpenGL ES 2, si le pilote ne répond pas. */
        const val MIN_TEXTURE_SIZE = 2048

        const val VERTEX_SHADER = """
            uniform mat4 uMatrix;
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        // Précision haute si possible : en moyenne, un texel sur mille seulement serait adressable.
        const val FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
