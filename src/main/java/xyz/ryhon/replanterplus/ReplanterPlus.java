package xyz.ryhon.replanterplus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.TextCollector;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

public class ReplanterPlus implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Replanter");
	private static final MinecraftClient mc = MinecraftClient.getInstance();
	static Boolean useIgnore = false;

	@Override
	public void onInitialize() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player instanceof ServerPlayerEntity || useIgnore || player.isSneaking())
				return ActionResult.PASS;

			ClientPlayerEntity p = (ClientPlayerEntity) player;
			BlockState state = world.getBlockState(hitResult.getBlockPos());

			if (isCrop(state)) {
				if (isGrown(state)) {
					breakAndReplant(p, hitResult);
					return ActionResult.SUCCESS;
				} else if (fndAndEquipSeed(player, Items.BONE_MEAL)) {
					useIgnore = true;
					mc.interactionManager.interactBlock(p, Hand.OFF_HAND, hitResult);
					useIgnore = false;
					return ActionResult.SUCCESS;
				}
			}

			return ActionResult.PASS;
		});
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
		useIgnore = true;

		Item seed = getSeed(player.getWorld().getBlockState(hit.getBlockPos()).getBlock());

		holdFortuneItem(player);
		mc.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());

		if (fndAndEquipSeed(player, seed)) {
			mc.interactionManager.interactBlock(player, Hand.OFF_HAND, hit.withBlockPos(
					hit.getBlockPos()));
		} else {
			player.sendMessage(
					Text.translatable(seed.getTranslationKey())
							.append(Text.translatable("replanter.gui.seed_not_found"))
							.setStyle(Style.EMPTY.withColor(0xFF0000)),
					true);
		}

		useIgnore = false;
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

	boolean fndAndEquipSeed(PlayerEntity p, Item item) {
		if (item == null)
			return false;

		PlayerInventory pi = p.getInventory();
		if (pi.getStack(PlayerInventory.OFF_HAND_SLOT).isOf(item))
			return true;
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			if (pi.getStack(i).isOf(item)) {
				pi.selectedSlot = i;
				mc.interactionManager.syncSelectedSlot();
				mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
						PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
				return true;
			}
		}
		return false;
	}

	void holdFortuneItem(PlayerEntity p) {
		int maxLevel = 0;
		int slot = -1;

		PlayerInventory pi = p.getInventory();
		for (int i = 0; i < pi.getHotbarSize(); i++) {
			int lvl = EnchantmentHelper.getLevel(Enchantments.FORTUNE, pi.getStack(i));
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
}