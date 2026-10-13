package com.oldwarden.entity;

import com.oldwarden.OldWardenConfig;
import com.oldwarden.goal.LeapSlamGoal;
import com.oldwarden.goal.SonicBoomGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

public class OldWardenEntity extends Monster implements GeoEntity {
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.warden_old.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.warden_old.walk");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public OldWardenEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 1000;
        this.setPathfindingMalus(PathType.LAVA, 0.0F);
        this.setPathfindingMalus(PathType.DANGER_FIRE, 0.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, 0.0F);
        this.setPathfindingMalus(PathType.WATER, 0.0F);
        this.setPersistenceRequired(); // never despawns
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, OldWardenConfig.ATTRIBUTE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, OldWardenConfig.SPEED)
                .add(Attributes.ATTACK_DAMAGE, OldWardenConfig.ATTACK_DAMAGE)
                .add(Attributes.ATTACK_KNOCKBACK, 2.0)
                .add(Attributes.FOLLOW_RANGE, OldWardenConfig.FOLLOW_RANGE)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 1.5)
                .add(Attributes.ARMOR, 20.0);
    }

    /** Natural spawn: deserts, any light level, on the surface, very rare, one at a time. */
    public static boolean checkSpawnRules(EntityType<OldWardenEntity> type, ServerLevelAccessor level,
                                          MobSpawnType reason, BlockPos pos, RandomSource random) {
        if (level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (reason == MobSpawnType.NATURAL) {
            if (!level.canSeeSky(pos)) return false;
            if (random.nextInt(OldWardenConfig.SPAWN_CHANCE) != 0) return false;
            if (!level.getEntitiesOfClass(OldWardenEntity.class, new AABB(pos).inflate(160)).isEmpty()) return false;
        }
        return Mob.checkMobSpawnRules(type, level, reason, pos, random);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new SonicBoomGoal(this));
        this.goalSelector.addGoal(2, new LeapSlamGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 50.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // Uses FOLLOW_RANGE (50 blocks) and needs line of sight.
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    public void launch(double x, double y, double z) {
        this.setDeltaMovement(x, y, z);
        this.hasImpulse = true;
    }

    @Override
    public void setTarget(@Nullable net.minecraft.world.entity.LivingEntity target) {
        boolean newAggro = target != null && this.getTarget() == null;
        super.setTarget(target);
        if (newAggro && !this.level().isClientSide) {
            this.triggerAnim("action", "roar");
            this.playSound(SoundEvents.WARDEN_ROAR, 4.0F, 0.8F);
        }
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (!this.level().isClientSide) this.triggerAnim("action", "attack");
        return super.doHurtTarget(target);
    }

    /** 1024 attribute cap -> scale incoming damage so the real pool is 2000 HP. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) amount *= OldWardenConfig.DAMAGE_SCALE;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypeTags.IS_FALL) || super.isInvulnerableTo(source);
    }

    @Override public boolean canBreatheUnderwater() { return true; }
    @Override public boolean isPushedByFluid() { return false; }
    @Override public boolean causeFallDamage(float dist, float mult, DamageSource src) { return false; }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!this.level().isClientSide) this.triggerAnim("action", "death");
    }

    @Override
    protected void tickDeath() { // keep the body 3s so the death animation plays
        ++this.deathTime;
        if (this.deathTime >= 60 && !this.level().isClientSide() && !this.isRemoved()) {
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.remove(Entity.RemovalReason.KILLED);
        }
    }

    @Override protected SoundEvent getAmbientSound() { return SoundEvents.WARDEN_AMBIENT; }
    @Override protected SoundEvent getHurtSound(DamageSource s) { return SoundEvents.WARDEN_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.WARDEN_DEATH; }
    @Override protected void playStepSound(BlockPos pos, BlockState state) { this.playSound(SoundEvents.WARDEN_STEP, 0.6F, 1.0F); }
    @Override protected float getSoundVolume() { return 2.0F; }

    // ---- GeckoLib ----
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "move", 5,
                state -> state.setAndContinue(state.isMoving() ? WALK : IDLE)));
        controllers.add(new AnimationController<>(this, "action", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", RawAnimation.begin().thenPlay("animation.warden_old.attack"))
                .triggerableAnim("roar", RawAnimation.begin().thenPlay("animation.warden_old.roar"))
                .triggerableAnim("sonic_boom", RawAnimation.begin().thenPlay("animation.warden_old.sonic_boom"))
                .triggerableAnim("leap", RawAnimation.begin().thenPlay("animation.warden_old.leap"))
                .triggerableAnim("death", RawAnimation.begin().thenPlay("animation.warden_old.death")));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
