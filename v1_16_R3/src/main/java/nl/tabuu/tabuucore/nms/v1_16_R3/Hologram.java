package nl.tabuu.tabuucore.nms.v1_16_R3;

import com.mojang.datafixers.util.Pair;
import net.minecraft.server.v1_16_R3.*;
import nl.tabuu.tabuucore.hologram.HologramItemLine;
import nl.tabuu.tabuucore.hologram.HologramLine;
import nl.tabuu.tabuucore.hologram.HologramStringLine;
import nl.tabuu.tabuucore.material.XMaterial;
import nl.tabuu.tabuucore.nms.wrapper.IHologram;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_16_R3.inventory.CraftItemStack;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class Hologram implements IHologram {
    private List<Map.Entry<EntityArmorStand, HologramLine>> _lines;
    private Set<UUID> _viewers;
    private boolean _destroyed, _global, _visible = true;
    private Location _location;

    public Hologram(Location location, HologramLine[] lines) {
        _lines = new LinkedList<>();
        _viewers = new HashSet<>();
        _destroyed = false;

        _location = location;

        setLines(lines);
    }

    @Override
    public void destroy() {
        setVisible(false);
        _lines.forEach(entry -> entry.getKey().die());
        _destroyed = true;
    }

    @Override
    public boolean isDestroyed() {
        return _destroyed;
    }

    @Override
    public void removePlayer(OfflinePlayer offlinePlayer) {
        _viewers.remove(offlinePlayer.getUniqueId());

        if (!offlinePlayer.isOnline()) return;
        Player player = (Player) offlinePlayer;

        update(player);
    }

    @Override
    public void addPlayer(OfflinePlayer offlinePlayer) {
        _viewers.add(offlinePlayer.getUniqueId());

        if (!offlinePlayer.isOnline()) return;
        Player player = (Player) offlinePlayer;

        update(player);
    }

    @Override
    public void setVisible(boolean visible) {
        _visible = visible;
        update(true);
    }

    @Override
    public boolean isVisible() {
        return _visible;
    }

    @Override
    public void setLines(HologramLine... lines) {
        if(lines.length == 0) {
            setVisible(false);
            _lines.clear();
            setVisible(true);

            return;
        }

        //Synchronizing new instances with old instances
        for(int i = 0; i < lines.length; i++) {
            if(lines.length != _lines.size()) break;
            HologramLine newLine = lines[i];
            HologramLine oldLine = _lines.get(i).getValue();

            newLine.setUpdating(!newLine.equals(oldLine));
        }

        // Calculating hologram size, and start location
        double size = Arrays.stream(lines).mapToDouble(line -> line.getTopSpacing() + line.getBottomSpacing()).sum();
        Location location = getLocation().clone().add(0, size - 1.975d, 0);

        // Adjusting ArmorStand count.
        int lineDelta = lines.length - _lines.size();
        if(lineDelta > 0) {
            for(int i = 0; i < lineDelta; i++)
                _lines.add(new HashMap.SimpleEntry<>(createArmorStand(location.clone()), null));
        } else if (lineDelta < 0) {
            for(int i = lineDelta; i < 0; i++)
                _lines.remove(0);
        }

        // Matching ArmorStands with lines
        for(int i = 0; i < _lines.size(); i++) {
            Map.Entry<EntityArmorStand, HologramLine> hologramLine = _lines.get(i);
            EntityArmorStand stand = hologramLine.getKey();
            HologramLine line = lines[i];

            if(i > 0) location.subtract(0, line.getTopSpacing(), 0);
            stand.enderTeleportTo(location.getX(), location.getY(), location.getZ());
            location.subtract(0, line.getBottomSpacing(), 0);

            hologramLine.setValue(line);
        }

        update();
    }

    @Override
    public List<HologramLine> getLines() {
        return _lines.stream().map(Map.Entry::getValue).collect(Collectors.toList());
    }

    @Override
    public void setLocation(Location location) {
        _location = location.clone();
        for(Map.Entry<EntityArmorStand, HologramLine> hologramLine : _lines) {
            hologramLine.getKey().enderTeleportTo(location.getX(), location.getY(), location.getZ());
        }
    }

    @Override
    public Location getLocation() {
        return _location.clone();
    }

    @Override
    public List<OfflinePlayer> getPlayers() {
        return _viewers.stream().map(Bukkit::getOfflinePlayer).collect(Collectors.toList());
    }

    @Override
    public boolean isGlobal() {
        return _global;
    }

    @Override
    public void setGlobal(boolean global) {
        if(global && !_global)
            Bukkit.getOnlinePlayers().forEach(this::addPlayer);

        _global = global;
    }

    private void update(EntityArmorStand stand, HologramLine line, Player player) {
        sendPacket(player, new PacketPlayOutEntityDestroy(stand.getId()));
        if(!isVisible()) return;

        stand.pitch = (float) line.getPitch();
        stand.yaw = (float) line.getYaw();

        if(line instanceof HologramStringLine) {
            HologramStringLine string = (HologramStringLine) line;
            stand.setCustomName(new ChatComponentText(string.getString()));
            stand.setCustomNameVisible(true);
        } else if (line instanceof HologramItemLine) {
            HologramItemLine item = (HologramItemLine) line;
            stand.setSlot(EnumItemSlot.HEAD, CraftItemStack.asNMSCopy(item.getItem()));
            stand.setCustomName(new ChatComponentText(" "));
        }

        sendPacket(player, new PacketPlayOutSpawnEntityLiving(stand));
        sendPacket(player, new PacketPlayOutEntityMetadata(stand.getId(), stand.getDataWatcher(), true));

        if(line instanceof HologramItemLine) {
            List<Pair<EnumItemSlot, ItemStack>> equipment = new ArrayList<>();
            HologramItemLine itemLine = (HologramItemLine) line;
            ItemStack defaultItem = CraftItemStack.asNMSCopy(XMaterial.AIR.parseItem());
            ItemStack item = CraftItemStack.asNMSCopy(itemLine.getItem());

            for(EnumItemSlot slot : EnumItemSlot.values())
                equipment.add(new Pair<>(slot, slot.equals(EnumItemSlot.HEAD) ? item : defaultItem));

            sendPacket(player, new PacketPlayOutEntityEquipment(stand.getId(), equipment));
        }
    }

    private void update(Player player) {
        for (Map.Entry<EntityArmorStand, HologramLine> hologramLine : _lines) {
            EntityArmorStand stand = hologramLine.getKey();
            HologramLine line = hologramLine.getValue();
            update(stand, line, player);
        }
    }

    private void update(boolean force) {
        for (Map.Entry<EntityArmorStand, HologramLine> hologramLine : _lines) {
            EntityArmorStand stand = hologramLine.getKey();
            HologramLine line = hologramLine.getValue();

            if(!line.isUpdating() && ! force) continue;

            for (OfflinePlayer offlinePlayer : getPlayers()) {
                if (!offlinePlayer.isOnline()) continue;
                Player player = (Player) offlinePlayer;

                update(stand, line, player);
            }

            line.setUpdating(false);
        }
    }

    private void update() {
        update(false);
    }

    private void sendPacket(Player player, Packet<PacketListenerPlayOut> packet) {
        ((CraftPlayer) player).getHandle().playerConnection.sendPacket(packet);
    }

    private EntityArmorStand createArmorStand(Location location) {
        if(location.getWorld() == null)
            throw new IllegalArgumentException("World cannot be null when creating a hologram.");

        WorldServer world = ((CraftWorld) location.getWorld()).getHandle();
        EntityArmorStand entity = new EntityArmorStand(world, location.getX(), location.getY(), location.getZ());

        entity.setNoGravity(true);
        entity.setInvisible(true);
        entity.setMarker(false);

        return entity;
    }
}