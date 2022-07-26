package net.lilfish.offlineplayersreworked;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.Pair;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.lilfish.offlineplayersreworked.interfaces.ServerPlayerEntityInterface;
import net.lilfish.offlineplayersreworked.npc.Npc;
import net.lilfish.offlineplayersreworked.storage.OfflineDatabase;
import net.lilfish.offlineplayersreworked.storage.models.NpcModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.MessageType;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.PositionImpl;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

import static net.minecraft.command.CommandSource.suggestMatching;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class OfflinePlayers implements DedicatedServerModInitializer {
    public static final String MOD_ID = "OfflinePlayersReworked";
    public static OfflineDatabase STORAGE = new OfflineDatabase();
    public static MinecraftServer server;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {
        LOGGER.info("Hello Fabric world!");
        // init DB
        STORAGE.init();
        // init command
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            if (dedicated) {
                try {
                    dispatcher.register(literal("offline")
                            .executes(OfflinePlayers::spawn)
                            .then(argument("action", StringArgumentType.word())
                                    .suggests((c, b) -> suggestMatching(new String[]{"place", "attack", "holdAttack", "jump", "dropItem"}, b))
                                    .executes(OfflinePlayers::spawn)
                                    .then(argument("interval", IntegerArgumentType.integer(0, 1000))
                                            .executes(OfflinePlayers::spawn)
                                            .then(argument("offset", IntegerArgumentType.integer(0, 1000))
                                                    .executes(OfflinePlayers::spawn)))));
                } catch (Exception exception) {
                    LOGGER.error("Exception while generating offline player:", exception);
                }
            }
        });
    }

    private static int spawn(CommandContext<ServerCommandSource> context) {
        LOGGER.info("Adding new offline player");
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player;
        try {
            player = source.getPlayer();
        } catch (CommandSyntaxException e) {
            LOGGER.error("Couldn't get player, not spawning offline player");
            return 0;
        }

//      Arguments
        String thisAction = "none";
        int thisInterval = 20;
        int thisOffset = -1;

        EntityPlayerActionPack.Action actionInterval = EntityPlayerActionPack.Action.interval(thisInterval);
        EntityPlayerActionPack.ActionType action_type = EntityPlayerActionPack.ActionType.ATTACK;

//      Try and get arguments
        try {
            thisAction = StringArgumentType.getString(context, "action");
        } catch (IllegalArgumentException ignored) {
        }
        try {
            thisInterval = IntegerArgumentType.getInteger(context, "interval");
            actionInterval = EntityPlayerActionPack.Action.interval(thisInterval * 20);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            thisOffset = IntegerArgumentType.getInteger(context, "offset");
            if (thisOffset != -1)
                actionInterval = EntityPlayerActionPack.Action.interval(thisInterval * 20, thisOffset);
        } catch (IllegalArgumentException ignored) {
        }

//      Create player
        Npc npc = Npc.createNpc(player, thisAction, thisInterval, thisOffset);

//      Check if NPC should have action, if so, add it.
        if (!Objects.equals(thisAction, "none")) {
            Pair<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> actionPair = getActionPair(thisAction, actionInterval);
            EntityPlayerActionPack.ActionType finalType = actionPair.first();
            EntityPlayerActionPack.Action finalActionInterval = actionPair.second();
            manipulate(npc, ap -> ap.start(finalType, finalActionInterval));
        }

        PositionImpl playerPosition = new PositionImpl(player.getX(), player.getY(), player.getZ());
//      Aggro update
        aggroUpdate(player, npc);
//      Disconnect player
        player.networkHandler.disconnect(Text.of("Offline player generated"));

        npc.refreshPositionAfterTeleport(playerPosition.getX(), playerPosition.getY(), playerPosition.getZ());
        return 1;
    }

    public static void respawnActiveNpcs() {
        STORAGE.getAllNPC().stream()
                .filter(npcModel -> !npcModel.isDead())
                .toList().forEach(npcModel -> {
                    Npc npc = Npc.respawnNpc(npcModel.getWorld(), npcModel.getNpc_name());

                    String npcActionString = npcModel.getAction();
                    int npcIntervalInTicks = npcModel.getInterval();
                    int npcOffset = npcModel.getOffset();

                    EntityPlayerActionPack.Action npcActionInterval;
                    EntityPlayerActionPack.ActionType action_type = EntityPlayerActionPack.ActionType.ATTACK;

                    npcActionInterval = EntityPlayerActionPack.Action.interval(npcIntervalInTicks * 20);

                    if (npcOffset != -1)
                        npcActionInterval = EntityPlayerActionPack.Action.interval(npcIntervalInTicks * 20, npcOffset);

                    if (!Objects.equals(npcActionString, "none")) {
                        Pair<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> actionPair = getActionPair(npcActionString, npcActionInterval);

                        EntityPlayerActionPack.ActionType finalType = actionPair.first();
                        EntityPlayerActionPack.Action finalActionInterval = actionPair.second();

                        manipulate(npc, ap -> ap.start(finalType, finalActionInterval));
                    }
                });
    }

    private static Pair<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> getActionPair(String actionString, EntityPlayerActionPack.Action action_interval) {
        EntityPlayerActionPack.Action action = action_interval;
        EntityPlayerActionPack.ActionType actionType = EntityPlayerActionPack.ActionType.ATTACK;
        switch (actionString) {
            case "attack" -> actionType = EntityPlayerActionPack.ActionType.ATTACK;
            case "holdAttack" -> {
                actionType = EntityPlayerActionPack.ActionType.ATTACK;
                action = EntityPlayerActionPack.Action.continuous();
            }
            case "place" -> actionType = EntityPlayerActionPack.ActionType.USE;
            case "jump" -> actionType = EntityPlayerActionPack.ActionType.JUMP;
            case "dropItem" -> actionType = EntityPlayerActionPack.ActionType.DROP_ITEM;
        }
        return Pair.of(actionType, action);
    }

    private static void manipulate(ServerPlayerEntity player, Consumer<EntityPlayerActionPack> action) {
        action.accept(((ServerPlayerEntityInterface) player).getActionPack());
    }

    public static void playerJoined(ServerPlayerEntity player) {
        NpcModel npcModel = STORAGE.findNPCByPlayer(player.getUuid());
        if (npcModel != null) {
            boolean correct;
            if (npcModel.isDead()) {
                correct = handleDeadNPC(player, npcModel);
                LOGGER.info("Handling dead NPC for player" + player.getName());
            } else {
                correct = handleAliveNPC(player, npcModel);
                LOGGER.info("Handling alive NPC for player" + player.getName());
            }
            // Remove NPC from DataBase
            if (correct) {
                STORAGE.removeNPC(player.getUuid());
                LOGGER.info("Removed " + player.getName() + " from Json DB");
            }
        }

    }

    private static boolean handleAliveNPC(ServerPlayerEntity player, NpcModel npc) {
        ServerPlayerEntity npcPlayer = player.server.getPlayerManager().getPlayer(npc.getNpc_id());
        if (npcPlayer != null) {
//          Set pos
            player.refreshPositionAfterTeleport(npcPlayer.getX(), npcPlayer.getY(), npcPlayer.getZ());
//          Copy inv.
            PlayerInventory npcInv = npcPlayer.getInventory();
            setInventory(player, npcInv);
//          Copy XP
            int points = Math.round(npcPlayer.getNextLevelExperience() * npcPlayer.experienceProgress);
            player.setExperienceLevel(npcPlayer.experienceLevel);
            player.setExperiencePoints(points);
//          Set status effects
            try {
                for (StatusEffectInstance statusEffect : npcPlayer.getStatusEffects()) {
                    player.addStatusEffect(statusEffect);
                    npcPlayer.removeStatusEffect(statusEffect.getEffectType());
                }
            } catch (Exception ignored) {
            }
            player.setHealth(npcPlayer.getHealth());
//          Set hunger
            player.getHungerManager().setExhaustion(npcPlayer.getHungerManager().getExhaustion());
            player.getHungerManager().setFoodLevel(npcPlayer.getHungerManager().getFoodLevel());
            player.getHungerManager().setSaturationLevel(npcPlayer.getHungerManager().getSaturationLevel());
//          Kill NPC
            npcPlayer.kill();
//          Aggro update
            aggroUpdate(npcPlayer, player);
        }
        return true;
    }

    private static boolean handleDeadNPC(ServerPlayerEntity player, NpcModel npc) {
//      Set pos
        player.refreshPositionAfterTeleport(npc.getX(), npc.getY(), npc.getZ());
//      Copy inv.
        PlayerInventory npcInv = STORAGE.getNPCInventory(npc);
        setInventory(player, npcInv);
//      Copy XP
        player.setExperiencePoints(npc.getXPpoints());
        player.setExperienceLevel(npc.getXPlevel());
//      Kill player
        if (player.interactionManager.getGameMode() == GameMode.DEFAULT) {
            player.getInventory().dropAll();
            player.setHealth(0);
            player.server.getPlayerManager().broadcast(Text.of(player.getDisplayName().asString() + " died: " + npc.getDeathMessage()), MessageType.CHAT, player.getUuid());
        }

        return true;
    }

    private static void setInventory(ServerPlayerEntity player, PlayerInventory npcInv) {
        player.getInventory().main.clear();
        for (int i = 0; i < npcInv.main.size(); i++) {
            player.getInventory().main.set(i, npcInv.main.get(i));
        }
        player.getInventory().armor.clear();
        for (int i = 0; i < npcInv.armor.size(); i++) {
            player.getInventory().armor.set(i, npcInv.armor.get(i));
        }
        player.getInventory().offHand.clear();
        for (int i = 0; i < npcInv.offHand.size(); i++) {
            player.getInventory().offHand.set(i, npcInv.offHand.get(i));
        }
    }

    public static void onWorldLoad(MinecraftServer mcServer) {
        // we need to set the server, so we can access it from the ping mixin
        server = mcServer;
    }

    public static void afterWorldLoad() {
        // respawn players
        respawnActiveNpcs();
    }

    private static void aggroUpdate(Entity firstEntity, Entity secondEntity) {
        var world = firstEntity.getWorld();
        var pathAwareEntities = world.getEntitiesByType(
                TypeFilter.instanceOf(PathAwareEntity.class),
                Box.of(firstEntity.getPos(), firstEntity.getX() + 128, firstEntity.getY() + 128, firstEntity.getZ() + 128),
                EntityPredicates.VALID_ENTITY);

        pathAwareEntities.stream()
                .filter(entity -> entity instanceof Angerable)
                .filter(entity -> ((Angerable) entity).getAngryAt() == firstEntity.getUuid())
                .forEach(angryEntity -> {
                    try {
                        angryEntity.setTarget((LivingEntity) secondEntity);
                        ((Angerable) angryEntity).setAngryAt(secondEntity.getUuid());
                    } catch (Exception ignored) {
                    }
                });
    }
}
