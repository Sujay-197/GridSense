package com.gridsense.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val VERTEX_SHADER = """
attribute vec4 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
void main() {
    gl_Position = a_Position;
    v_TexCoord = a_TexCoord;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 v_TexCoord;
uniform samplerExternalOES sTexture;
void main() {
    gl_FragColor = texture2D(sTexture, v_TexCoord);
}
"""

private fun floats(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }

/**
 * Draws the live camera image so you can see what ARCore is looking at. ARCore also needs this
 * texture: it writes each camera frame into it, and tracking does not run without one.
 */
class CameraBackground {

    var textureId = -1
        private set

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0

    private val quadCoords = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texCoords = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    /** Must run on the GL thread, from onSurfaceCreated. */
    fun create() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                texCoords
            )
        }
        // No camera image has arrived yet.
        if (frame.timestamp == 0L) return

        quadCoords.position(0)
        texCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
