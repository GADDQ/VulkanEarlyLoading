package top.earthstudio.vulkanearlyloading.mixin;

import com.mojang.blaze3d.platform.NativeLibrariesBootstrap;
import org.lwjgl.vulkan.VK;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NativeLibrariesBootstrap.class)
public class MixinNativeLibrariesBootstrap {

    @Inject(method = "tryLoadingVulkan", at = @At("HEAD"), cancellable = true)
    private static void vulkanearlyloading$fixTryLoadingVulkan(CallbackInfoReturnable<Boolean> cir) {
        // 如果我们的早期窗口已经成功加载并持有了 Vulkan FunctionProvider
        // 说明 Vulkan 必定可用，直接返回 true！
        // 从而避免原版重复调用 VK.create() 触发 IllegalStateException，导致返回 false
        if (VK.getFunctionProvider() != null) {
            System.out.println("Vulkan is already active from Early Window. Forcing tryLoadingVulkan() -> true!");
            cir.setReturnValue(true);
        }
    }
}