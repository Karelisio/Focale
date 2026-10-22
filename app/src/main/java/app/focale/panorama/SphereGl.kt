package app.focale.panorama

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Maillage de la zone [area] d'une sphère ([SphereMesh]) en tampons OpenGL, commun aux photos
 * ([SphereRenderer]) et aux vidéos 360° ([VideoSphereRenderer]).
 */
internal class SphereBuffers(area: SphereArea) {
    private val mesh = SphereMesh.build(area)
    private val positions: FloatBuffer = floats(mesh.positions)
    private val texCoords: FloatBuffer = floats(mesh.texCoords)
    private val indices: ShortBuffer = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().apply {
            put(mesh.indices)
            position(0)
        }

    /** Fil OpenGL : dessine les triangles, attributs `aPosition` et `aTexCoord` du programme en cours. */
    fun draw(positionHandle: Int, texCoordHandle: Int) {
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indices.size, GLES20.GL_UNSIGNED_SHORT, indices)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private companion object {
        fun floats(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}

/** Programmes OpenGL ES 2 des sphères. */
internal object GlPrograms {

    /** Fil OpenGL : programme lié des deux shaders, 0 si le pilote le refuse (raison dans le journal). */
    fun link(vertexSource: String, fragmentSource: String, tag: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.w(tag, "Programme refusé : ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
