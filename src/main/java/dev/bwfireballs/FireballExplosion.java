package dev.bwfireballs;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** BedWars-style fireball explosion: strong knockback, light damage, wool-style block breaking, no fire. */
public final class FireballExplosion {
	private FireballExplosion() {}

	// ---- Tuning (Hypixel BedWars-like) ----
	private static final float BLOCK_POWER = 3.0F;      // block-breaking reach
	private static final double KNOCKBACK_RADIUS = 6.0;  // players within this are launched
	private static final double KNOCKBACK_STRENGTH = 3.2;
	private static final double MIN_LIFT = 0.9;          // extra upward push so ground players always launch
	private static final float DAMAGE_OTHERS = 6.0F;     // at point blank (3 hearts)
	private static final float DAMAGE_SELF = 1.0F;       // thrower takes very little
	private static final int MAX_BLOCKS = 400;           // hard cap
	private static final boolean BREAK_BLOCKS = false;  // false = blocks are never destroyed

	public static void explode(ServerLevel level, LargeFireball fireball, Vec3 center) {
		Entity owner = fireball.getOwner();

		if (BREAK_BLOCKS) {
			breakBlocks(level, center);
		}
		hurtAndLaunch(level, fireball, owner, center);

		// Effects (kept small to avoid lag)
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.FLAME, center.x, center.y, center.z, 12, 0.6, 0.6, 0.6, 0.05);
		level.sendParticles(ParticleTypes.SMOKE, center.x, center.y, center.z, 12, 0.6, 0.6, 0.6, 0.05);
		BedwarsFireballs.play(level, center, SoundEvents.GENERIC_EXPLODE, 4.0F,
				(1.0F + (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F) * 0.7F);
	}

	private static boolean isProtected(BlockState state) {
		return state.is(BlockTags.PLANKS)
				|| state.is(BlockTags.LOGS)
				|| state.is(BlockTags.IMPERMEABLE)      // glass blocks
				|| state.is(BlockTags.WITHER_IMMUNE)    // bedrock, barrier, etc.
				|| state.is(Blocks.END_STONE)
				|| state.is(Blocks.OBSIDIAN);
	}

	private static void breakBlocks(ServerLevel level, Vec3 center) {
		RandomSource random = level.getRandom();
		Set<BlockPos> toBreak = new HashSet<>();

		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) {
						continue;
					}
					double dx = x / 15.0 * 2.0 - 1.0;
					double dy = y / 15.0 * 2.0 - 1.0;
					double dz = z / 15.0 * 2.0 - 1.0;
					double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
					dx /= len;
					dy /= len;
					dz /= len;

					float ray = BLOCK_POWER * (0.7F + random.nextFloat() * 0.6F);
					double cx = center.x, cy = center.y, cz = center.z;

					while (ray > 0.0F) {
						BlockPos pos = BlockPos.containing(cx, cy, cz);
						BlockState state = level.getBlockState(pos);
						if (!state.isAir()) {
							float resistance = isProtected(state) ? 1000.0F : state.getBlock().getExplosionResistance();
							ray -= (resistance + 0.3F) * 0.3F;
							if (ray > 0.0F && state.getDestroySpeed(level, pos) >= 0.0F && !isProtected(state)) {
								toBreak.add(pos);
							}
						}
						cx += dx * 0.3;
						cy += dy * 0.3;
						cz += dz * 0.3;
						ray -= 0.225F;
					}
				}
			}
		}

		int count = 0;
		for (BlockPos pos : toBreak) {
			if (count++ >= MAX_BLOCKS) {
				break;
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); // no drops, like BedWars
		}
	}

	private static void hurtAndLaunch(ServerLevel level, LargeFireball fireball, Entity owner, Vec3 center) {
		AABB box = new AABB(center.x - KNOCKBACK_RADIUS, center.y - KNOCKBACK_RADIUS, center.z - KNOCKBACK_RADIUS,
				center.x + KNOCKBACK_RADIUS, center.y + KNOCKBACK_RADIUS, center.z + KNOCKBACK_RADIUS);
		List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box);
		DamageSource source = level.damageSources().explosion(fireball, owner);

		for (LivingEntity victim : victims) {
			if (victim.isSpectator() || !victim.isAlive()) {
				continue;
			}
			Vec3 body = victim.getBoundingBox().getCenter();
			double dist = body.distanceTo(center);
			if (dist >= KNOCKBACK_RADIUS) {
				continue;
			}
			double factor = 1.0 - dist / KNOCKBACK_RADIUS;

			// Direction away from the blast; straight-down/zero-distance cases fall back to "up".
			Vec3 dir = body.subtract(center);
			dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : dir.normalize();

			Vec3 push = dir.scale(KNOCKBACK_STRENGTH * factor);
			push = new Vec3(push.x, Math.max(push.y, MIN_LIFT * factor), push.z);
			victim.setDeltaMovement(victim.getDeltaMovement().scale(0.3).add(push));
			victim.hurtMarked = true;
			if (victim instanceof ServerPlayer player) {
				player.connection.send(new ClientboundSetEntityMotionPacket(player));
			}

			float damage = (float) (factor * (victim == owner ? DAMAGE_SELF : DAMAGE_OTHERS));
			if (damage >= 0.5F) {
				victim.hurtServer(level, source, damage);
			}
		}
	}
}
