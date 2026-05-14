package com.example.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * BotManager — основная логика бота.
 *
 * Функции:
 *   1) Автоматическая атака ближайших враждебных мобов
 *   2) Автоматическое поедание еды при голоде (hunger < 16)
 *   3) Автоматическое питьё Зловещей бутылки (Ominous Bottle) когда нет эффекта Дурного знамения
 *
 * Переключается клавишей K. Работает только в режиме выживания.
 * Имитирует поведение человека: случайные задержки, плавный поворот к цели.
 */
public class BotManager {

	// === НАСТРОЙКИ ===
	private static final double ATTACK_RANGE = 3.0;
	private static final int EAT_HUNGER_THRESHOLD = 16;
	private static final int MIN_ATTACK_DELAY = 12;
	private static final int MAX_ATTACK_DELAY = 25;
	private static final float MAX_ROTATION_SPEED = 8.0f;

	// === СОСТОЯНИЕ ===
	private boolean enabled = false;
	private int attackCooldown = 0;
	private int actionDelay = 0;
	private int bottleCooldown = 0;
	private int swordSlot = -1;
	private final Random random = new Random();

	// === КОНЕЧНЫЙ АВТОМАТ ИСПОЛЬЗОВАНИЯ ПРЕДМЕТОВ ===
	// 0 = ничего, 1 = ждём смену слота, 2 = держим правую кнопку
	private int useState = 0;
	private int useTimer = 0;
	private boolean wasDrinkingBottle = false;

	// === REFLECTION ACCESS TO Inventory.selected ===
	private static final java.lang.reflect.Field SELECTED_FIELD;

	static {
		try {
			SELECTED_FIELD = Inventory.class.getDeclaredField("selected");
			SELECTED_FIELD.setAccessible(true);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException("Failed to access Inventory.selected field", e);
		}
	}

	private int getSelectedSlot(Inventory inv) {
		try {
			return SELECTED_FIELD.getInt(inv);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	private void setSelectedSlot(Minecraft client, Inventory inv, int slot) {
		try {
			SELECTED_FIELD.setInt(inv, slot);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		// Отправляем пакет серверу чтобы он знал какой слот выбран
		if (client.getConnection() != null) {
			client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	/**
	 * Переключить состояние бота (вкл/выкл)
	 */
	public void toggle(Minecraft client) {
		enabled = !enabled;
		if (client.player != null) {
			String status = enabled ? "\u00a7a\u0412\u043a\u043b\u044e\u0447\u0451\u043d" : "\u00a7c\u0412\u044b\u043a\u043b\u044e\u0447\u0435\u043d";
			client.player.displayClientMessage(
				Component.literal("\u00a76[AutoBot] \u00a7f\u0411\u043e\u0442 " + status),
				true
			);
		}
	}

	/**
	 * Основной тик бота — вызывается каждый клиентский тик
	 */
	public void tick(Minecraft client) {
		if (!enabled) return;

		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		MultiPlayerGameMode gameMode = client.gameMode;

		// Базовые проверки
		if (player == null || level == null || gameMode == null) return;
		if (player.isSpectator() || player.isDeadOrDying()) return;
		if (player.isCreative()) return;

		// === ДЕРЖИМ ПРАВУЮ КНОПКУ (пьём/едим) ===
		if (useState == 2) {
			client.options.keyUse.setDown(true);
			useTimer--;

			// Таймаут — что-то пошло не так, отпускаем
			if (useTimer <= 0) {
				client.options.keyUse.setDown(false);
				switchToSword(client, player);
				useState = 0;
				actionDelay = 40;
				return;
			}

			// Первые 40 тиков (~2 сек) просто держим, не проверяем
			if (useTimer > 20) return;

			// После — проверяем закончилось ли
			if (!player.isUsingItem()) {
				client.options.keyUse.setDown(false);
				switchToSword(client, player);
				useState = 0;
				actionDelay = 40;
				// Кулдаун бутылки — ТОЛЬКО если пили бутылку, не еду!
				if (wasDrinkingBottle) {
					wasDrinkingBottle = false;
					bottleCooldown = 4800; // 4 минуты (4800 тиков)
				}
				return;
			}

			// Всё ещё пьёт/ест — держим дальше
			return;
		}

		// === ЖДЁМ ПОСЛЕ СМЕНЫ СЛОТА ===
		if (useState == 1) {
			useTimer--;
			if (useTimer > 0) return;
			// Слот переключён — зажимаем правую кнопку
			useState = 2;
			useTimer = 60; // защита от зависания
			return;
		}

		// Если игрок сам ест/пьёт — не мешаем
		if (player.isUsingItem()) return;

		// Пауза между действиями
		if (actionDelay > 0) {
			actionDelay--;
			return;
		}

		// === ПРИОРИТЕТ ДЕЙСТВИЙ ===

		// Приоритет 1: Есть если голоден
		if (player.getFoodData().getFoodLevel() < EAT_HUNGER_THRESHOLD) {
			if (findAndSwitch(client, player, gameMode, true)) return;
		}

		// Приоритет 2: Выпить Зловещую бутылку
		if (bottleCooldown > 0) {
			bottleCooldown--;
		} else if (player.getEffect(MobEffects.BAD_OMEN) == null) {
			wasDrinkingBottle = true;
			if (findAndSwitch(client, player, gameMode, false)) return;
			wasDrinkingBottle = false;
		}

		// Приоритет 3: Атаковать ближайших враждебных мобов
		if (attackCooldown > 0) {
			attackCooldown--;
			return;
		}

		tryAttack(client, player, level, gameMode);
	}

	// ==================== ПОИСК И ПЕРЕКЛЮЧЕНИЕ ====================

	/**
	 * Найти еду/бутылку, переключить слот, и на следующем тике
	 * gameMode.useItem() будет вызван — это то же самое что правый клик.
	 *
	 * @param food true = ищем еду, false = ищем Ominous Bottle
	 */
	private boolean findAndSwitch(Minecraft client, LocalPlayer player, MultiPlayerGameMode gameMode, boolean food) {
		Inventory inv = player.getInventory();

		// Проверяем текущий слот — предмет уже в руке
		int current = getSelectedSlot(inv);
		ItemStack inHand = inv.getItem(current);
		if (food ? isFood(inHand) : inHand.is(Items.OMINOUS_BOTTLE)) {
			// Предмет уже в руке — сразу зажимаем
			useState = 2;
			useTimer = 60;
			return true;
		}

		// Ищем в хотбаре
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getItem(i);
			if (food ? isFood(stack) : stack.is(Items.OMINOUS_BOTTLE)) {
				setSelectedSlot(client, inv, i);
				useState = 1; // ждём смену слота
				useTimer = 5;
				return true;
			}
		}

		return false;
	}

	// ==================== МЕЧ ====================

	/**
	 * Проверить что предмет — это меч (по ID в реестре)
	 */
	private boolean isSword(ItemStack stack) {
		if (stack.isEmpty()) return false;
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
			.getKey(stack.getItem()).getPath();
		return id.contains("sword");
	}

	// ==================== ПЕРЕКЛЮЧЕНИЕ НА МЕЧ ====================

	/**
	 * Вернуть меч в руку после еды/зелья
	 */
	private void switchToSword(Minecraft client, LocalPlayer player) {
		if (swordSlot < 0) return;
		Inventory inv = player.getInventory();
		if (getSelectedSlot(inv) != swordSlot) {
			setSelectedSlot(client, inv, swordSlot);
		}
	}

	// ==================== АТАКА ====================

	/**
	 * Найти ближайшего враждебного моба и атаковать его
	 */
	private void tryAttack(Minecraft client, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode) {
		// Переключаемся на меч
		Inventory inv = player.getInventory();
		if (swordSlot < 0 || swordSlot != getSelectedSlot(inv)) {
			for (int i = 0; i < 9; i++) {
				if (isSword(inv.getItem(i))) {
					if (getSelectedSlot(inv) != i) {
						setSelectedSlot(client, inv, i);
					}
					swordSlot = i;
					break;
				}
			}
		}

		// Находим монстров в радиусе
		AABB searchBox = player.getBoundingBox().inflate(ATTACK_RANGE);
		List<Monster> monsters = level.getEntitiesOfClass(
			Monster.class,
			searchBox,
			mob -> mob.isAlive() && player.distanceTo(mob) <= ATTACK_RANGE
		);

		if (monsters.isEmpty()) return;
		monsters.sort(Comparator.comparingDouble(player::distanceTo));
		Monster nearest = monsters.get(0);

		// Плавно поворачиваемся к ближайшему
		lookAtEntity(player, nearest);

		// === LEGIT ПРОВЕРКА ===
		// client.hitResult — то что игра сама вычислила для прицела
		// Type.BLOCK = стена на пути (Игра УЖЕ проверила блоки!)
		// Type.ENTITY = моб под прицелом, стены НЕТ
		// Type.MISS = ничего
		var hit = client.hitResult;
		if (hit == null) return;

		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) {
			Entity hitEntity = ((EntityHitResult) hit).getEntity();
			if (hitEntity instanceof Monster monster && monster.isAlive()) {
				float str = player.getAttackStrengthScale(1.0f);
				if (str >= 0.9f && player.distanceTo(monster) <= ATTACK_RANGE) {
					gameMode.attack(player, monster);
					player.swing(InteractionHand.MAIN_HAND);
					attackCooldown = MIN_ATTACK_DELAY + random.nextInt(MAX_ATTACK_DELAY - MIN_ATTACK_DELAY + 1);
				}
			}
		}
	}

	// ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

	/**
	 * Плавно повернуть игрока к сущности
	 */
	private void lookAtEntity(LocalPlayer player, Entity target) {
		Vec3 eyePos = player.getEyePosition();
		double targetY = target.getY() + target.getBbHeight() * 0.6;
		Vec3 targetPos = new Vec3(target.getX(), targetY, target.getZ());
		Vec3 direction = targetPos.subtract(eyePos);

		double horizontalDist = Math.sqrt(
			direction.x * direction.x + direction.z * direction.z
		);

		float targetYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
		float targetPitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontalDist));

		float currentYaw = player.getYRot();
		float currentPitch = player.getXRot();

		float yawDiff = wrapDegrees(targetYaw - currentYaw);
		float pitchDiff = targetPitch - currentPitch;

		float speed = MAX_ROTATION_SPEED + random.nextFloat() * 3.0f;

		if (Math.abs(yawDiff) > speed) {
			yawDiff = Math.signum(yawDiff) * speed;
		}
		if (Math.abs(pitchDiff) > speed) {
			pitchDiff = Math.signum(pitchDiff) * speed;
		}

		player.setYRot(currentYaw + yawDiff);
		player.setXRot(currentPitch + pitchDiff);
	}

	private float wrapDegrees(float value) {
		value = value % 360;
		if (value >= 180) value -= 360;
		if (value < -180) value += 360;
		return value;
	}

	private boolean isFood(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return stack.has(DataComponents.FOOD);
	}

	public boolean isEnabled() {
		return enabled;
	}
}