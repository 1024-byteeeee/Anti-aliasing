package dev.antialiasing.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.antialiasing.TaaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
//#if MC>=12000
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Vector3f;
import org.joml.Quaternionf;
//#elseif MC>=11900
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import org.joml.Quaternionf;
//#endif

@Mixin(InventoryScreen.class)
@SuppressWarnings("unused") // Loaded by Mixin configuration at runtime.
public abstract class GuiEntityMixin {
    //#if MC>=12000
    @WrapMethod(method = "renderEntityInInventory")
    private static void taa$preview(GuiGraphics gui, float x, float y, float size, Vector3f translation,
                                    Quaternionf rotation, Quaternionf camera, LivingEntity entity, Operation<Void> original) {
        gui.flush();
        Quaternionf savedCamera = camera == null ? null : new Quaternionf(camera);
        TaaClient.GUI_SSAA.render(x, y, size, entity, () -> {
            if (camera != null) camera.set(savedCamera);
            original.call(gui, x, y, size, translation, rotation, camera, entity);
        });
    }
    //#elseif MC>=11900
    //$$ @WrapMethod(method = "renderEntityInInventory")
    //$$ private static void taa$preview(PoseStack pose, int x, int y, int size, Quaternionf rotation,
    //$$                                 Quaternionf camera, LivingEntity entity, Operation<Void> original) {
    //$$     Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    //$$     Quaternionf savedCamera = camera == null ? null : new Quaternionf(camera);
    //$$     TaaClient.GUI_SSAA.render(x, y, size, entity, () -> {
    //$$         if (camera != null) camera.set(savedCamera);
    //$$         original.call(pose, x, y, size, rotation, camera, entity);
    //$$     });
    //$$ }
    //#else
    //$$ @WrapMethod(method = "renderEntityInInventory")
    //$$ private static void taa$preview(int x, int y, int size, float mouseX, float mouseY, LivingEntity entity, Operation<Void> original) {
    //$$     Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
    //$$     TaaClient.GUI_SSAA.render(x, y, size, entity, () -> original.call(x, y, size, mouseX, mouseY, entity));
    //$$ }
    //#endif
}
