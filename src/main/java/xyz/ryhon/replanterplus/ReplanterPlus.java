package xyz.ryhon.replanterplus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class ReplanterPlus implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Replanter");
	private static final MinecraftClient mc = MinecraftClient.getInstance();
	static Boolean useIgnore = false;

	public static Boolean enabled = true;
	public static Boolean sneakToggle = true;
	public static int useDelay = 4;
	public static Boolean missingItemNotifications = true;
	public static Boolean autoSwitch = true;
	public static Boolean requireSeedHeld = false;

	int ticks = 0;
	final int autoSaveTicks = 20 * 60 * 3;

	@Override
	public void onInitialize() {
		loadConfig();

		String bindCategory = "category.replanter";
		KeyBinding menuBind = new KeyBinding("key.replanter.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
				bindCategory);
		KeyBindingHelper.registerKeyBinding(menuBind);
		KeyBinding toggleBind = new KeyBinding("key.replanter.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
				bindCategory);
		KeyBindingHelper.registerKeyBinding(toggleBind);

		ClientTickEvents.END_CLIENT_TICK.register((client) -> {
			ticks++;
			if (ticks == autoSaveTicks) {
				ticks = 0;
				saveConfig();
			}

			if (menuBind.wasPressed())
				client.setScreen(new ConfigScreen(null));

			if (toggleBind.wasPressed())
				enabled = !enabled;
		});

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player instanceof ServerPlayerEntity || useIgnore)
				return ActionResult.PASS;

			if (!enabled || (sneakToggle && player.isSneaking()))
				return ActionResult.PASS;

			ClientPlayerEntity p = (ClientPlayerEntity) player;
			BlockState state = world.getBlockState(hitResult.getBlockPos());

			if (state.getBlock() instanceof CocoaBlock cb) {
				if (!cb.isFertilizable(world, hitResult.getBlockPos(), state)) {
					breakAndReplantCocoa(p, state, hitResult);
					return ActionResult.SUCCESS;
				} else {
					Hand h = findAndEquipSeed(player, Items.BONE_MEAL);
					if (h != null) {
						useIgnore = true;
						mc.interactionManager.interactBlock(p, h, hitResult);
						useIgnore = false;
						return ActionResult.SUCCESS;
					}
				}
			} else if (isCrop(state)) {
				if (isGrown(state)) {
					breakAndReplant(p, hitResult);
					return ActionResult.SUCCESS;
				} else {
					Hand h = findAndEquipSeed(player, Items.BONE_MEAL);
					if (h != null) {
						useIgnore = true;
						mc.interactionManager.interactBlock(p, h, hitResult);
						useIgnore = false;
						return ActionResult.SUCCESS;
					}
				}
			}

			return ActionResult.PASS;
		});
	}

	Boolean findInstamineTool(ClientPlayerEntity p, BlockState state, BlockPos pos) {
		if (state.calcBlockBreakingDelta(p, p.getWorld(), pos) >= 1f)
			return true;

		if (!autoSwitch)
			return false;

		int currentSlot = p.getInventory().selectedSlot;
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			p.getInventory().selectedSlot = i;
			if (state.calcBlockBreakingDelta(p, p.getWorld(), pos) >= 1f) {
				mc.interactionManager.syncSelectedSlot();
				return true;
			}
		}
		p.getInventory().selectedSlot = currentSlot;

		return false;
	}

	Boolean isCrop(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock)
			return true;
		else if (block instanceof NetherWartBlock)
			return true;
		else if (block instanceof PitcherCropBlock)
			return PitcherCropBlock.isLowerHalf(state);
		else if (block == Blocks.TORCHFLOWER || block == Blocks.TORCHFLOWER_CROP)
			return true;

		return false;
	}

	Boolean isGrown(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock crop)
			return crop.isMature(state);
		else if (block instanceof NetherWartBlock)
			return (Integer) state.get(NetherWartBlock.AGE) == 3;
		else if (block instanceof PitcherCropBlock pcb)
			// Interacting with upper half will reject the use packet
			// because it's too far away
			return pcb.isFullyGrown(state) && PitcherCropBlock.isLowerHalf(state);
		if (block == Blocks.TORCHFLOWER)
			return true;

		return false;
	}

	void breakAndReplant(ClientPlayerEntity player, BlockHitResult hit) {
		Item seed = getSeed(player.getWorld().getBlockState(hit.getBlockPos()).getBlock());
		Hand h = findAndEquipSeed(player, seed);
		if (requireSeedHeld && h == null) {
			sendMissingItemMessage(player, seed);
			return;
		}

		holdFortuneItem(player);
		mc.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());

		if (h != null) {
			useIgnore = true;
			mc.interactionManager.interactBlock(player, h, hit.withBlockPos(
					hit.getBlockPos()));
			useIgnore = false;
		} else
			sendMissingItemMessage(player, seed);
		mc.itemUseCooldown = useDelay;
	}

	void breakAndReplantCocoa(ClientPlayerEntity p, BlockState state, BlockHitResult hitResult) {
		if (findInstamineTool(p, state, hitResult.getBlockPos())) {
			Item seed = state.getBlock().asItem();
			Hand h = findAndEquipSeed(p, seed);

			if (requireSeedHeld && h == null) {
				sendMissingItemMessage(p, seed);
				return;
			}

			mc.interactionManager.attackBlock(hitResult.getBlockPos(), hitResult.getSide());
			if (h != null) {
				Direction dir = (Direction) state.get(CocoaBlock.FACING);

				float x, y, z;
				x = dir.getOffsetX();
				y = dir.getOffsetY();
				z = dir.getOffsetZ();
				BlockHitResult placeHit = BlockHitResult.createMissed(
						hitResult.getPos().add(x, y, z), dir.getOpposite(),
						hitResult.getBlockPos().add(dir.getVector()));

				useIgnore = true;
				mc.interactionManager.interactBlock(p, h, placeHit);
				useIgnore = false;
			} else
				sendMissingItemMessage(p, seed);
			mc.itemUseCooldown = useDelay;
		}
	}

	Item getSeed(Block block) {
		if (block instanceof CropBlock cb) {
			return cb.asItem();
		} else if (block instanceof NetherWartBlock) {
			return Items.NETHER_WART;
		} else if (block instanceof PitcherCropBlock) {
			return Items.PITCHER_POD;
		} else if (block == Blocks.TORCHFLOWER) {
			return Items.TORCHFLOWER_SEEDS;
		}

		return null;
	}

	Hand findAndEquipSeed(PlayerEntity p, Item item) {
		if (item == null)
			return null;

		PlayerInventory pi = p.getInventory();
		if (pi.getStack(pi.selectedSlot).isOf(item))
			return Hand.MAIN_HAND;
		if (pi.getStack(PlayerInventory.OFF_HAND_SLOT).isOf(item))
			return Hand.OFF_HAND;

		if (!autoSwitch)
			return null;

		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			if (pi.getStack(i).isOf(item)) {
				pi.selectedSlot = i;
				mc.interactionManager.syncSelectedSlot();
				mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
						PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
				return Hand.OFF_HAND;
			}
		}
		return null;
	}

	void holdFortuneItem(PlayerEntity p) {
		if(!autoSwitch)
			return;

		int maxLevel = 0;
		int slot = -1;

		PlayerInventory pi = p.getInventory();
		Registry<Enchantment> enchantRegistry = p.getWorld().getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT).get();
		Optional<RegistryEntry.Reference<Enchantment>> fortune = enchantRegistry.getEntry(Enchantments.FORTUNE.getValue());
		// Server removed the Fortune enchantment????
		if (!fortune.isPresent())
			return;

		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			int lvl = EnchantmentHelper.getLevel(fortune.get(), pi.getStack(i));
			if (lvl > maxLevel) {
				maxLevel = lvl;
				slot = i;
			}
		}

		if (slot != -1) {
			pi.selectedSlot = slot;
			mc.interactionManager.syncSelectedSlot();
		}
	}

	void sendMissingItemMessage(PlayerEntity player, Item seed) {
		if (missingItemNotifications)
			player.sendMessage(
					Text.translatable(seed.getTranslationKey())
							.append(Text.translatable(
									autoSwitch ? "replanter.gui.seed_not_in_hotbar" : "replanter.gui.seed_not_in_hand"))
							.setStyle(Style.EMPTY.withColor(0xFF0000)),
					true);
	}

	static Path configDir = FabricLoader.getInstance().getConfigDir().resolve("replanterplus");
	static Path configFile = configDir.resolve("config.json");

	static void loadConfig() {
		try {
			Files.createDirectories(configDir);
			if (!Files.exists(configFile))
				return;

			String str = Files.readString(configFile);
			JsonObject jo = (JsonObject) JsonParser.parseString(str);

			if (jo.has("enabled"))
				enabled = jo.get("enabled").getAsBoolean();
			if (jo.has("sneakToggle"))
				sneakToggle = jo.get("sneakToggle").getAsBoolean();
			if (jo.has("useDelay"))
				useDelay = jo.get("useDelay").getAsInt();
			if (jo.has("missingItemNotifications"))
				missingItemNotifications = jo.get("missingItemNotifications").getAsBoolean();
			if (jo.has("autoSwitch"))
				autoSwitch = jo.get("autoSwitch").getAsBoolean();
			if (jo.has("requireSeedHeld"))
				requireSeedHeld = jo.get("requireSeedHeld").getAsBoolean();
		} catch (Exception e) {
			LOGGER.error("Failed to load config", e);
		}
	}

	static void saveConfig() {
		JsonObject jo = new JsonObject();

		jo.add("enabled", new JsonPrimitive(enabled));
		jo.add("sneakToggle", new JsonPrimitive(sneakToggle));
		jo.add("useDelay", new JsonPrimitive(useDelay));
		jo.add("missingItemNotifications", new JsonPrimitive(missingItemNotifications));
		jo.add("autoSwitch", new JsonPrimitive(autoSwitch));
		jo.add("requireSeedHeld", new JsonPrimitive(requireSeedHeld));

		try {
			Files.createDirectories(configDir);
			Files.writeString(configFile, new Gson().toJson(jo));
		} catch (Exception e) {
			LOGGER.error("Failed to save config", e);
		}
	}
}