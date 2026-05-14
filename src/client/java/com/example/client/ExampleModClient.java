package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class ExampleModClient implements ClientModInitializer {

	// Используем встроенную категорию GAMEPLAY для клавиши бота
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.GAMEPLAY;

	private static KeyMapping botToggleKey;
	private static final BotManager botManager = new BotManager();

	@Override
	public void onInitializeClient() {
		// Register the K keybinding to toggle the bot
		botToggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.autobot.toggle",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			CATEGORY
		));

		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			while (botToggleKey.consumeClick()) {
				botManager.toggle(client);
			}
			botManager.tick(client);
		});
	}
}