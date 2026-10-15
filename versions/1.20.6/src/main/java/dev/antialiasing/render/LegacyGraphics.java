package dev.antialiasing.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
//#if MC>=11700
import com.mojang.blaze3d.pipeline.TextureTarget;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/** OpenGL backend for versions preceding Minecraft's GPU abstraction. */
final class LegacyGraphics implements AutoCloseable {
    private int resolve, residual, present, downsample, guiPresent, vao, data, filterData;

    void initGui() {
        if (vao != 0) return;
        try {
            downsample = program("ssaa_downsample", false);
            guiPresent = program("gui_present", false);
            vao = GL30.glGenVertexArrays();
            filterData = GL15.glGenBuffers();
        } catch (RuntimeException e) { close(); throw e; }
    }

    void compositeGui(int framebuffer, RenderTarget source, int x, int y) {
        begin(guiPresent, source, false);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL11.glViewport(x, y, source.width, source.height);
        GL11.glEnable(GL11.GL_BLEND);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        sampler(guiPresent, "SourceColor", 0, source.getColorTextureId());
        GL20.glUniform2i(GL20.glGetUniformLocation(guiPresent, "Origin"), x, y);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    void init() {
        if (vao != 0) return;
        try {
            resolve = program("resolve", false);
            residual = program("resolve", true);
            present = program("present", false);
            downsample = program("ssaa_downsample", false);
            vao = GL30.glGenVertexArrays();
            data = GL15.glGenBuffers();
            filterData = GL15.glGenBuffers();
        } catch (RuntimeException e) { close(); throw e; }
    }

    static RenderTarget target(int width, int height, boolean depth) {
        return target(width, height, depth, false);
    }
    static RenderTarget target(int width, int height, boolean depth, boolean floatingDepth) {
        //#if MC>=11700
        RenderTarget target = new TextureTarget(width, height, depth, false);
        //#else
        //$$ RenderTarget target = new RenderTarget(width, height, depth, false);
        //#endif
        // DEPTH32F retains the packed foreground mask and distant surface depth.
        if (depth && floatingDepth) {
            texture(0, target.getDepthTextureId());
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F,
                    width, height, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        }
        target.setFilterMode(GL11.GL_LINEAR);
        target.setClearColor(0, 0, 0, 0);
        target.clear(false);
        return target;
    }

    static void texture(int unit, int texture) {
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        RenderSystem.bindTexture(texture);
        if (GL.getCapabilities().OpenGL33) GL33.glBindSampler(unit, 0);
    }

    void upload(ByteBuffer bytes) {
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, data);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, bytes, GL15.GL_STREAM_DRAW);
    }

    void resolve(RenderTarget source, RenderTarget world, RenderTarget old,
                 RenderTarget oldResidual, RenderTarget destination, boolean precision) {
        int shader = precision ? residual : resolve;
        begin(shader, destination, !precision);
        sampler(shader, "CurrentColor", 0, source.getColorTextureId());
        sampler(shader, "CurrentDepth", 1, source.getDepthTextureId());
        sampler(shader, "WorldDepth", 2, world.getDepthTextureId());
        sampler(shader, "HistoryColor", 3, old.getColorTextureId());
        sampler(shader, "HistoryDepth", 4, old.getDepthTextureId());
        sampler(shader, "HistoryResidual", 5, oldResidual.getColorTextureId());
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, data);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    void present(RenderTarget destination, RenderTarget color, RenderTarget history) {
        begin(present, destination, false);
        sampler(present, "ResolvedColor", 0, color.getColorTextureId());
        sampler(present, "HistoryColor", 1, history.getColorTextureId());
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, data);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    void filter(RenderTarget source, RenderTarget destination, int scale, boolean vertical) {
        filter(source, destination, scale, vertical, false);
    }
    void filter(RenderTarget source, RenderTarget destination, int scale, boolean vertical, boolean alpha) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bytes = stack.calloc(16);
            bytes.putFloat(0, scale).putFloat(4, vertical ? 1 : 0).putFloat(8, alpha ? 1 : 0);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, filterData);
            GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, bytes, GL15.GL_STREAM_DRAW);
        }
        begin(downsample, destination, false);
        sampler(downsample, "SourceColor", 0, source.getColorTextureId());
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, filterData);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private void begin(int program, RenderTarget destination, boolean depth) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, destination.frameBufferId);
        GL11.glViewport(0, 0, destination.width, destination.height);
        GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
        //#if MC<11700
        //$$ GL11.glDisable(GL11.GL_ALPHA_TEST);
        //#endif
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(depth);
        if (depth) { GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthFunc(GL11.GL_ALWAYS); }
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
    }

    static void copy(RenderTarget from, RenderTarget to, int mask) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL30.glBlitFramebuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height, mask, GL11.GL_NEAREST);
    }

    private static void sampler(int shader, String name, int unit, int id) {
        texture(unit, id);
        GL20.glUniform1i(GL20.glGetUniformLocation(shader, name), unit);
    }

    static String source(String name) {
        String path = name.startsWith("/") ? name : "/assets/taa/shaders/core/" + name;
        try (InputStream input = LegacyGraphics.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing shader " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read TAA shader", e); }
    }

    private static int program(String name, boolean precision) {
        int vertex = 0, fragment = 0, program = GL20.glCreateProgram();
        try {
            vertex = shader(GL20.GL_VERTEX_SHADER, source("fullscreen.vsh"));
            String text = source(name + ".fsh");
            if (precision) text = text.replace("#version 150", "#version 150\n#define RESIDUAL_PASS");
            fragment = shader(GL20.GL_FRAGMENT_SHADER, text);
            GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
            GL30.glBindFragDataLocation(program, 0, "fragColor");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            if (!name.equals("gui_present")) {
                int block = GL31.glGetUniformBlockIndex(program, name.equals("ssaa_downsample") ? "SsaaData" : "TaaData");
                GL31.glUniformBlockBinding(program, block, 0);
            }
            return program;
        } catch (RuntimeException e) { GL20.glDeleteProgram(program); throw e; }
        finally { if (vertex != 0) GL20.glDeleteShader(vertex); if (fragment != 0) GL20.glDeleteShader(fragment); }
    }

    static int shader(int type, String text) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, text); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader); GL20.glDeleteShader(shader);
            throw new IllegalStateException(error);
        }
        return shader;
    }

    @Override public void close() {
        for (int program : new int[]{resolve, residual, present, downsample, guiPresent}) if (program != 0) GL20.glDeleteProgram(program);
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (data != 0) GL15.glDeleteBuffers(data);
        if (filterData != 0) GL15.glDeleteBuffers(filterData);
        resolve = residual = present = downsample = guiPresent = vao = data = filterData = 0;
    }

    /** Restore actual GL bindings and Minecraft's cached texture bindings after every pass. */
    static final class State implements AutoCloseable {
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int buffer = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        final int slot = GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 0);
        final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int[] viewport = new int[4], textures = new int[6], samplers = new int[6];
        final boolean[] colors = new boolean[4];
        //#if MC<11700
        //$$ final int[] caps = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST, GL11.GL_ALPHA_TEST};
        //#else
        final int[] caps = {GL11.GL_BLEND, GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST, GL11.GL_STENCIL_TEST};
        //#endif
        final boolean[] enabled = new boolean[caps.length];
        State() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4); GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colors[i] = mask.get(i) != 0;
            }
            for (int i = 0; i < caps.length; i++) enabled[i] = GL11.glIsEnabled(caps[i]);
            for (int i = 0; i < textures.length; i++) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                if (GL.getCapabilities().OpenGL33) samplers[i] = GL33.glGetIntegeri(GL33.GL_SAMPLER_BINDING, i);
            }
            RenderSystem.activeTexture(active);
        }
        @Override public void close() {
            for (int i = 0; i < textures.length; i++) {
                texture(i, textures[i]);
                if (GL.getCapabilities().OpenGL33) GL33.glBindSampler(i, samplers[i]);
            }
            RenderSystem.activeTexture(active);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, slot); GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer);
            GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glDepthMask(depthMask); GL11.glDepthFunc(depthFunction);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            GL11.glColorMask(colors[0], colors[1], colors[2], colors[3]);
            for (int i = 0; i < caps.length; i++) { if (enabled[i]) GL11.glEnable(caps[i]); else GL11.glDisable(caps[i]); }
        }
    }
}
