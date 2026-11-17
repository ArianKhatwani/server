package ru.chaossmp;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChaosSMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combat = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> teleports = new ConcurrentHashMap<>();
    private final Map<UUID, Location> teleportOrigins = new ConcurrentHashMap<>();
    private Connection db;
    private NamespacedKey boardKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        boardKey = new NamespacedKey(this, "board_entity");
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "stats.db").getAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS stats (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, kills INTEGER DEFAULT 0, deaths INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, best_streak INTEGER DEFAULT 0, playtime INTEGER DEFAULT 0)");
            }
        } catch (SQLException ex) {
            getLogger().severe("Could not open stats database: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String cmd : List.of("spawn", "setspawn", "stats", "top")) {
            PluginCommand c = getCommand(cmd);
            if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) { ensurePlayer(p); sessions.put(p.getUniqueId(), System.currentTimeMillis()); }
        long refresh = Math.max(10, getConfig().getLong("boards.refresh-seconds", 30));
        Bukkit.getScheduler().runTaskTimer(this, this::refreshBoards, refresh * 20, refresh * 20);
        getLogger().info("ChaosSMP 1.1.0 enabled.");
    }

    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) saveSession(p);
        teleports.values().forEach(BukkitRunnable::cancel);
        if (db != null) try { db.close(); } catch (SQLException ignored) {}
    }

    private void ensurePlayer(OfflinePlayer p) {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO stats(uuid,name) VALUES(?,?)")) {
            ps.setString(1, p.getUniqueId().toString()); ps.setString(2, p.getName() == null ? "Unknown" : p.getName()); ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
        updateName(p);
    }
    private void updateName(OfflinePlayer p) {
        try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET name=? WHERE uuid=?")) {
            ps.setString(1, p.getName() == null ? "Unknown" : p.getName()); ps.setString(2, p.getUniqueId().toString()); ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
    private void saveSession(Player p) {
        Long start = sessions.remove(p.getUniqueId());
        if (start == null || db == null) return;
        long seconds = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        try (PreparedStatement ps = db.prepareStatement("UPDATE stats SET playtime=playtime+? WHERE uuid=?")) {
            ps.setLong(1, seconds); ps.setString(2, p.getUniqueId().toString()); ps.executeUpdate();
        } catch (SQLException e) { getLogger().warning(e.getMessage()); }
    }
    @EventHandler public void join(PlayerJoinEvent e) { ensurePlayer(e.getPlayer()); sessions.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }
    @EventHandler public void quit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); cancelTeleport(p, false); saveSession(p); combat.remove(p.getUniqueId());
    }
    @EventHandler public void death(PlayerDeathEvent e) {
        Player victim=e.getEntity(); ensurePlayer(victim);
        add(victim, "deaths", 1); add(victim, "streak", -999999);
        Player killer=victim.getKiller();
        if (killer != null) { ensurePlayer(killer); add(killer, "kills", 1); add(killer, "streak", 1); setBest(killer); }
    }
    @EventHandler public void damage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (teleports.containsKey(p.getUniqueId())) { cancelTeleport(p, true); }
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent by) {
            Player attacker = null;
            if (by.getDamager() instanceof Player direct) attacker=direct;
            else if (by.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) attacker=shooter;
            if (attacker != null && attacker != p) {
                long until=System.currentTimeMillis()+getConfig().getLong("teleport.combat-tag-seconds",15)*1000;
                combat.put(p.getUniqueId(), until); combat.put(attacker.getUniqueId(), until);
                if (teleports.containsKey(attacker.getUniqueId())) cancelTeleport(attacker, true);
            }
        }
    }
    @EventHandler public void move(PlayerMoveEvent e) {
        if (!getConfig().getBoolean("teleport.cancel-on-move", true) || !teleports.containsKey(e.getPlayer().getUniqueId())) return;
        Location a=e.getFrom(), b=e.getTo();
        if (b != null && (a.getBlockX()!=b.getBlockX() || a.getBlockY()!=b.getBlockY() || a.getBlockZ()!=b.getBlockZ()))
            cancelTeleport(e.getPlayer(), true);
    }

    private void add(Player p, String field, long amount) {
        try (PreparedStatement ps=db.prepareStatement("UPDATE stats SET "+field+"=MAX(0,"+field+"+?) WHERE uuid=?")) {
            ps.setLong(1,amount); ps.setString(2,p.getUniqueId().toString()); ps.executeUpdate();
        } catch(SQLException ex){getLogger().warning(ex.getMessage());}
    }
    private void setBest(Player p) {
        try(PreparedStatement ps=db.prepareStatement("UPDATE stats SET best_streak=MAX(best_streak,streak) WHERE uuid=?")){
            ps.setString(1,p.getUniqueId().toString()); ps.executeUpdate();
        }catch(SQLException ignored){}
    }
    private String fmt(long sec) {
        long h=sec/3600, m=(sec%3600)/60;
        return h+"ч "+m+"м";
    }
    private long liveTime(UUID id) {
        Long start=sessions.get(id); return start==null?0:Math.max(0,(System.currentTimeMillis()-start)/1000);
    }
    private Map<String,Object> stats(UUID id) {
        Map<String,Object> out=new HashMap<>();
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM stats WHERE uuid=?")){
            ps.setString(1,id.toString()); try(ResultSet rs=ps.executeQuery()){if(rs.next()){
                for(String k:List.of("name","kills","deaths","streak","best_streak","playtime")) out.put(k,rs.getObject(k));
                out.put("playtime",((Number)out.get("playtime")).longValue()+liveTime(id)); return out;
            }}
        }catch(SQLException ignored){} return out;
    }
    private String pretty(String key) {
        return switch(key){case "kills"->"Убийства";case "deaths"->"Смерти";case "streak"->"Лучшая серия";default->"Время игры";};
    }
    private List<Map<String,Object>> ranking(String key) {
        String column=key.equals("playtime")?"playtime":key.equals("streak")?"best_streak":key;
        List<Map<String,Object>> list=new ArrayList<>();
        try(Statement s=db.createStatement(); ResultSet rs=s.executeQuery("SELECT uuid,name,"+column+" AS score FROM stats ORDER BY score DESC,name ASC LIMIT 10")){
            while(rs.next()){
                Map<String,Object> row=new HashMap<>(); row.put("uuid",rs.getString("uuid"));row.put("name",rs.getString("name"));row.put("score",rs.getLong("score"));
                if(key.equals("playtime")) row.put("score",rs.getLong("score")+liveTime(UUID.fromString(rs.getString("uuid"))));
                list.add(row);
            }
        }catch(SQLException ignored){} return list;
    }
    private void msg(CommandSender s,String text){s.sendMessage(mm.deserialize(getConfig().getString("messages.prefix","")+" "+text));}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        switch(command.getName().toLowerCase(Locale.ROOT)) {
            case "setspawn" -> {
                if(!(sender instanceof Player p)) {sender.sendMessage("Only players can set spawn.");return true;}
                if(!p.hasPermission("chaossmp.setspawn")){msg(p,"<red>Нет прав.");return true;}
                getConfig().set("spawn.world",p.getWorld().getName());getConfig().set("spawn.x",p.getLocation().getX());
                getConfig().set("spawn.y",p.getLocation().getY());getConfig().set("spawn.z",p.getLocation().getZ());
                getConfig().set("spawn.yaw",p.getLocation().getYaw());getConfig().set("spawn.pitch",p.getLocation().getPitch());
                saveConfig(); refreshBoards(); msg(p,"<green>Спавн и таблицы лидеров установлены!"); return true;
            }
            case "spawn" -> {
                if(!(sender instanceof Player p)) return true;
                if(!getConfig().contains("spawn.world")){msg(p,"<red>Спавн ещё не установлен. Используй /setspawn.");return true;}
                Long until=combat.get(p.getUniqueId());
                if(until!=null && until>System.currentTimeMillis()){msg(p,"<red>Нельзя телепортироваться во время PvP.");return true;}
                cancelTeleport(p,false);
                int seconds=Math.max(1,getConfig().getInt("teleport.countdown-seconds",5));
                teleportOrigins.put(p.getUniqueId(),p.getLocation().clone());
                BukkitRunnable task=new BukkitRunnable(){int left=seconds;
                    public void run(){
                        if(!p.isOnline()){cancel();teleports.remove(p.getUniqueId());return;}
                        Long tag=combat.get(p.getUniqueId());
                        if(tag!=null && tag>System.currentTimeMillis()){cancelTeleport(p,true);return;}
                        if(left<=0){Location loc=spawnLocation();if(loc!=null)p.teleport(loc);msg(p,"<green>Ты телепортирован на спавн.");teleports.remove(p.getUniqueId());teleportOrigins.remove(p.getUniqueId());cancel();return;}
                        msg(p,"<gray>Телепортация через <gold>"+left+"<gray> сек.");left--;
                    }};
                teleports.put(p.getUniqueId(),task);task.runTaskTimer(this,0,20);return true;
            }
            case "stats" -> {
                OfflinePlayer target=sender instanceof Player p?p:Bukkit.getOfflinePlayer("Console");
                if(args.length>0) target=Bukkit.getOfflinePlayer(args[0]);
                else if(sender instanceof Player p) target=p;
                if(target==null || target.getName()==null){msg(sender,"<red>Игрок не найден.");return true;}
                Map<String,Object> st=stats(target.getUniqueId()); if(st.isEmpty()){msg(sender,"<red>Статистика пока отсутствует.");return true;}
                msg(sender,"<gold><bold>✦ СТАТИСТИКА "+st.get("name")+" ✦");
                msg(sender,"<yellow>⚔ Убийства: <white>"+st.get("kills")+"  <red>☠ Смерти: <white>"+st.get("deaths"));
                msg(sender,"<light_purple>🔥 Текущая серия: <white>"+st.get("streak")+"  <gold>Рекорд: <white>"+st.get("best_streak"));
                msg(sender,"<aqua>⌛ Время игры: <white>"+fmt((Long)st.get("playtime")));return true;
            }
            case "top" -> {
                if(args.length==0){msg(sender,"<yellow>Используй: /top kills|deaths|streak|playtime");return true;}
                String key=args[0].toLowerCase(Locale.ROOT);if(!List.of("kills","deaths","streak","playtime").contains(key)){msg(sender,"<red>Неизвестный рейтинг.");return true;}
                msg(sender,"<gold><bold>✦ ТОП "+pretty(key).toUpperCase(Locale.ROOT)+" ✦");
                int i=1;for(Map<String,Object> r:ranking(key)){String score=key.equals("playtime")?fmt(((Number)r.get("score")).longValue()):String.valueOf(r.get("score"));
                    msg(sender,"<gray>"+i+". <white>"+r.get("name")+" <dark_gray>» <gold>"+score);i++;}
                return true;
            }
        }
        return false;
    }
    private void cancelTeleport(Player p,boolean announce){
        BukkitRunnable r=teleports.remove(p.getUniqueId());if(r!=null){r.cancel();teleportOrigins.remove(p.getUniqueId());if(announce)msg(p,"<red>Телепортация отменена.");}
    }
    private Location spawnLocation(){
        World w=Bukkit.getWorld(getConfig().getString("spawn.world"));if(w==null)return null;
        return new Location(w,getConfig().getDouble("spawn.x"),getConfig().getDouble("spawn.y"),getConfig().getDouble("spawn.z"),
                (float)getConfig().getDouble("spawn.yaw"),(float)getConfig().getDouble("spawn.pitch"));
    }
    private void clearBoards(World w){
        for(Entity e:w.getEntities()) if(e.getPersistentDataContainer().has(boardKey,PersistentDataType.BYTE)) e.remove();
    }
    private void refreshBoards(){
        Location spawn=spawnLocation();if(spawn==null)return; World w=spawn.getWorld();if(w==null)return;
        clearBoards(w);
        for(String key:List.of("kills","deaths","streak","playtime")){
            ConfigurationSection sec=getConfig().getConfigurationSection("boards."+key);if(sec==null)continue;
            double angle=Math.toRadians(sec.getDouble("angle",0));
            double radius=getConfig().getDouble("boards.radius",4.0);
            Location base=spawn.clone().add(Math.cos(angle)*radius,getConfig().getDouble("boards.height",2.8),Math.sin(angle)*radius);
            String title=sec.getString("title","<gold><bold>TOP</bold></gold>");
            spawnText(w,base,title);
            List<Map<String,Object>> rows=ranking(key);int rank=1;
            for(Map<String,Object> row:rows){
                String medal=rank==1?"<gold>❶":rank==2?"<gray>❷":rank==3?"<color:#CD7F32>❸":"<dark_gray>"+rank+".";
                String score=key.equals("playtime")?fmt(((Number)row.get("score")).longValue()):String.valueOf(row.get("score"));
                Location line=base.clone().subtract(0,rank*getConfig().getDouble("boards.line-spacing",0.27),0);
                spawnText(w,line,medal+" <white>"+row.get("name")+" <dark_gray>• <aqua>"+score);
                rank++;
            }
        }
    }
    private void spawnText(World w,Location loc,String text){
        TextDisplay display=(TextDisplay)w.spawnEntity(loc,EntityType.TEXT_DISPLAY);
        display.text(mm.deserialize(text));display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);display.setShadowed(true);display.setDefaultBackground(false);
        display.getPersistentDataContainer().set(boardKey,PersistentDataType.BYTE,(byte)1);
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){
        if(c.getName().equalsIgnoreCase("top")&&args.length==1)return List.of("kills","deaths","streak","playtime");
        return Collections.emptyList();
    }
}
