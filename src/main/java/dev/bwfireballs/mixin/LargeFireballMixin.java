package dev.bwfireballs.mixin;

import dev.bwfireballs.BedwarsFireballs;
import dev.bwfireballs.FireballExplosion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LargeFireball.class)
public abstract class LargeFireballMixin {

	/** Replaces the vanilla impact for fireballs launched by this mod only (tagged). */
	@Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
	private void bwfireballs$onHit(HitResult hitResult, CallbackInfo ci) {
		LargeFireball self = (LargeFireball) (Object) this;
		if (!self.getTags().contains(BedwarsFireballs.TAG)) {
			return; // ghast fireballs etc. stay vanilla
		}
		ci.cancel();
		if (self.isRemoved()) {
			return; // never explode twice
		}
		if (self.level() instanceof ServerLevel serverLevel) {
			Vec3 motion = self.getDeltaMovement();
			Vec3 back = motion.lengthSqr() > 1.0E-6 ? motion.normalize().scale(0.3) : Vec3.ZERO;
			Vec3 center = hitResult.getLocation().subtract(back);
			FireballExplosion.explode(serverLevel, self, center);
		}
		self.discard();
	}
}
