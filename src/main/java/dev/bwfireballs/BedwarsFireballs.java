package dev.bwfireballs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class BedwarsFireballs implements ModInitializer {
	public static final String TAG = "bwfireballs_fireball";

	/** Fireball flight speed in blocks per tick. */
	private static final double SPEED = 1.0;
	/** Cooldown between throws, in ticks (0.5 s). */
	private static final int COOLDOWN_TICKS = 10;
	/** Fireballs that have not hit anything after this many ticks are removed (no infinite projectiles). */
	private static final int MAX_LIFETIME_TICKS = 20 * 8;

	private static final Map<UUID, Long> LAST_THROW = new HashMap<>();
	private static final List<LargeFireball> ACTIVE = new ArrayList<>();

	@Override
	public void onInitialize() {
		// Right-click in the air
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.is(Items.FIRE_CHARGE) || player.isSpectator()) {
				return InteractionResult.PASS;
			}
			if (level instanceof ServerLevel serverLevel) {
				launch(serverLevel, player, stack);
			}
			return InteractionResult.SUCCESS;
		});

		// Right-click on a block: also throws, except when sneaking (then vanilla fire lighting works)
		// or when the block has a block entity (chests, furnaces ... keep opening normally).
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (hand != InteractionHand.MAIN_HAND && !stack.is(Items.FIRE_CHARGE)) {
				return InteractionResult.PASS;
			}
			if (!stack.is(Items.FIRE_CHARGE) || player.isSpectator() || player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (level.getBlockEntity(hit.getBlockPos()) != null) {
				return InteractionResult.PASS;
			}
			if (level instanceof ServerLevel serverLevel) {
				launch(serverLevel, player, stack);
			}
			return InteractionResult.SUCCESS;
		});

		// Lifetime cleanup
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ACTIVE.isEmpty()) {
				return;
			}
			ACTIVE.removeIf(fb -> {
				if (fb.isRemoved()) {
					return true;
				}
				if (fb.tickCount > MAX_LIFETIME_TICKS) {
					fb.discard();
					return true;
				}
				return false;
			});
		});
	}

	private static void launch(ServerLevel level, Player player, ItemStack stack) {
		long now = level.getGameTime();
		Long last = LAST_THROW.get(player.getUUID());
		if (last != null && now - last < COOLDOWN_TICKS && now >= last) {
			return;
		}
		LAST_THROW.put(player.getUUID(), now);

		Vec3 look = player.getLookAngle().normalize();
		LargeFireball fireball = new LargeFireball(level, player, look, 0);
		fireball.addTag(TAG);
		Vec3 eye = player.getEyePosition();
		fireball.setPos(eye.x + look.x * 0.8, eye.y - 0.25 + look.y * 0.8, eye.z + look.z * 0.8);
		fireball.setDeltaMovement(look.scale(SPEED));
		// Vanilla fireballs accelerate and drag by 0.95/tick: terminal speed = 19 * accelerationPower.
		fireball.accelerationPower = SPEED / 19.0;
		level.addFreshEntity(fireball);
		ACTIVE.add(fireball);

		play(level, eye, SoundEvents.BLAZE_SHOOT, 1.0F, 1.0F);

		stack.consume(1, player); // does nothing in Creative
	}

	// Overloads so this compiles whether a vanilla sound constant is a SoundEvent or a Holder<SoundEvent>.
	public static void play(Level level, Vec3 pos, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, volume, pitch);
	}

	public static void play(Level level, Vec3 pos, Holder<SoundEvent> sound, float volume, float pitch) {
		play(level, pos, sound.value(), volume, pitch);
	}
}
