package com.example.veinminer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vein Miner: quebrou um minerio (ou pedra) com a picareta, o veio inteiro vem junto.
 *
 * Regras:
 *  - precisa estar segurando uma picareta (tag minecraft:pickaxes)
 *  - por padrao so funciona AGACHADO (shift). Troque REQUIRE_SNEAK pra false pra funcionar sempre.
 *  - so quebra blocos que a picareta consegue minerar com drop (ex.: picareta de madeira nao pega ferro)
 *  - cada bloco gasta 1 de durabilidade; a picareta nunca quebra por causa do mod
 */
public class VeinMiner implements ModInitializer {
    public static final String MOD_ID = "veinminer";

    /** true = so ativa agachado. false = ativa sempre. */
    private static final boolean REQUIRE_SNEAK = true;

    /** Limite de blocos por veio, pra nao travar o servidor. */
    private static final int MAX_BLOCKS = 64;

    /** Grupos de blocos considerados "o mesmo veio" (ex.: ferro normal + ferro deepslate). */
    private static final List<TagKey<Block>> GROUPS = List.of(
            BlockTags.COAL_ORES,
            BlockTags.IRON_ORES,
            BlockTags.COPPER_ORES,
            BlockTags.GOLD_ORES,
            BlockTags.REDSTONE_ORES,
            BlockTags.LAPIS_ORES,
            BlockTags.DIAMOND_ORES,
            BlockTags.EMERALD_ORES,
            BlockTags.BASE_STONE_OVERWORLD,
            BlockTags.BASE_STONE_NETHER
    );

    /** Evita o evento disparar de novo enquanto a gente mesmo quebra os blocos. */
    private static boolean mining = false;

    @Override
    public void onInitialize() {
        PlayerBlockBreakEvents.AFTER.register(VeinMiner::onBlockBroken);
    }

    private static void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (mining) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(player instanceof ServerPlayer)) return;
        if (REQUIRE_SNEAK && !player.isShiftKeyDown()) return;

        TagKey<Block> group = groupOf(state);
        if (group == null) return;

        ItemStack tool = player.getMainHandItem();
        if (!tool.is(ItemTags.PICKAXES)) return;

        boolean creative = player.isCreative();
        List<BlockPos> vein = findVein(serverLevel, pos, group, tool, creative);
        if (vein.isEmpty()) return;

        mining = true;
        try {
            for (BlockPos veinPos : vein) {
                if (!creative && tool.isDamageableItem()
                        && tool.getDamageValue() >= tool.getMaxDamage() - 1) {
                    break; // deixa a picareta com 1 de durabilidade
                }

                serverLevel.destroyBlock(veinPos, !creative, player);

                if (!creative) {
                    tool.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
                }
            }
        } finally {
            mining = false;
        }
    }

    /** Flood fill (26 vizinhos) a partir do bloco quebrado, so por blocos do mesmo grupo. */
    private static List<BlockPos> findVein(ServerLevel level, BlockPos origin, TagKey<Block> group,
                                           ItemStack tool, boolean creative) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> result = new ArrayList<>();

        visited.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < MAX_BLOCKS) {
            BlockPos current = queue.poll();

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos next = current.offset(dx, dy, dz);
                        if (!visited.add(next)) continue;

                        BlockState nextState = level.getBlockState(next);
                        if (!nextState.is(group)) continue;
                        if (!creative && !tool.isCorrectToolForDrops(nextState)) continue;

                        result.add(next);
                        queue.add(next);

                        if (result.size() >= MAX_BLOCKS) return result;
                    }
                }
            }
        }

        return result;
    }

    private static TagKey<Block> groupOf(BlockState state) {
        for (TagKey<Block> group : GROUPS) {
            if (state.is(group)) return group;
        }
        return null;
    }
}
