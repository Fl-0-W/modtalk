package com.example.mobtalk.mixin;

import com.example.mobtalk.State;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Мобы с перемирием вообще не могут выбрать игрока целью: не бьют, не стреляют, не взрываются. */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void mobtalk$blockTarget(LivingEntity target, CallbackInfo ci) {
        if (target instanceof PlayerEntity && State.isTruce(((MobEntity) (Object) this).getUuid())) {
            ci.cancel();
        }
    }
}
