package dev.intensetomato.dfu.modules.treecutters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import dev.intensetomato.dfu.mixin.PlayerTabOverlayAccessor;
import dev.intensetomato.dfu.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@SuppressWarnings("deprecation")
public final class TreecuttersModule implements dev.intensetomato.dfu.api.DfuModule {
    private static TreecuttersModule INSTANCE;
    private static final String PREFIX = "§8[§aTC§8] §7";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("treecutters-utilities.json");

    private static final Pattern ACTION_LOGS = Pattern.compile("([0-9.,]+[kKmMbBtTqQ]?) Logs");
    private static final Pattern ACTION_STOCK = Pattern.compile("Stock Price: ([0-9.,]+[kKmMbBtTqQ]?)");
    private static final Pattern ACTION_LPS = Pattern.compile("\\+([0-9.,]+[kKmMbBtTqQ]?)/s");
    private static final Pattern STOCKS_SIGN = Pattern.compile("Stocks: ([0-9.,]+[kKmMbBtTqQ]?)");
    private static final Pattern COST_LORE = Pattern.compile("Costs ([0-9.,]+[kKmMbBtTqQ]?) logs to upgrade\\.");
    private static final Pattern LEVEL_LORE = Pattern.compile("Level ([0-9,]+)/([0-9,]+)");
    private static final Pattern WEATHER_LOGS = Pattern.compile("Logs: ([0-9.,]+[kKmMbBtTqQ]?)");
    private static final Pattern WEATHER_COST = Pattern.compile("Cost: -([0-9.,]+[kKmMbBtTqQ]?)/s/p");
    private static final Pattern WEATHER_BOOST = Pattern.compile("Global Boost: \\+([0-9.]+)%");
    private static final Pattern PLAYERS = Pattern.compile("Players Online: (\\d+)");
    private static final Pattern WEATHER_STATUS = Pattern.compile("Status: (Inactive|Rain|Thunderstorm|Severe Thunderstorm|Hurricane|Tornado|Supercell|Multi-Vortex Supercell|EF-6 Multi-Vortex Supercell)");
    private static final Pattern TICKER = Pattern.compile("^(?:GREAT DEPRESSION!|STOCK TICKER!).*$", Pattern.CASE_INSENSITIVE);

    private Config config;
    private long ticks;
    private double logs = -1, stockPrice = -1, stocks = -1;
    private double displayedLps = -1;
    private final Deque<Sample> lpsSamples = new ArrayDeque<>();
    private double sessionGain, peakLps;
    private long sessionStartMs;
    private String weather = "Unknown";
    private double weatherLogs = -1;
    private long weatherSyncMs = -1;
    private int playersOnline = -1;
    private double weatherCostPerPlayer = -1;
    private double weatherBoost = -1;
    private double weatherContributed;
    private int observedTreeLevel = -1, observedFortuneLevel = -1;
    private final Map<String, UpgradeSnapshot> observedUpgrades = new LinkedHashMap<>();
    private final Deque<String> stockEvents = new ArrayDeque<>();
    private boolean openHudEditorNextTick;
    private boolean openConfigNextTick;
    private boolean hudHidden;
    private boolean loaded;
    private boolean treecuttersDetected;
    private long lastTreecuttersActionbar;
    private long suppressLpsUntilMs;
    private Consumer<String> actionBarListener;
    
    @Override public void onLoad(dev.intensetomato.dfu.api.DfuContext context) {
        INSTANCE = this;
        loaded = true;
        config = loadConfig();
        sessionStartMs = System.currentTimeMillis();
        actionBarListener = this::parseActionbar;
        dev.intensetomato.dfu.api.DfuEvents.onActionBar(actionBarListener);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!loaded) return;
            String text = message.getString();
            if (overlay) parseActionbar(text); else onMessage(text);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!loaded) return;
            ticks++;
            if (openConfigNextTick) {
                openConfigNextTick = false;
                client.setScreen(new ConfigScreen(client.screen));
            }
            if (openHudEditorNextTick) {
                openHudEditorNextTick = false;
                client.setScreen(new HudEditorScreen());
            }
            if (ticks % 10 == 0) {
                parseTabHeader(client);
                scanStockSigns(client);
                trimLpsSamples();
            }
        });

        HudRenderCallback.EVENT.register((g, dt) -> { if (loaded) renderHud(g); });
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> ScreenEvents.afterRender(screen).register((s, g, mx, my, d) -> { if (loaded) renderUpgradeHint(s, g); }));

        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!loaded || !level.isClientSide()) return InteractionResult.PASS;
            BlockPos p = hit.getBlockPos();
            if (p.equals(new BlockPos(101, 6, 62)) && logs >= (player.isShiftKeyDown()?10_000_000:1_000_000)) weatherContributed += player.isShiftKeyDown()?10_000_000:1_000_000;
            if (player.isShiftKeyDown() || stockPrice < 0) return InteractionResult.PASS;
            boolean buy = p.equals(new BlockPos(4103, 5, 20041));
            boolean sell = p.equals(new BlockPos(4103, 5, 20047));
            if (buy && config.buyCeiling > 0 && stockPrice > config.buyCeiling) {
                local("Buy blocked: §f" + compact(stockPrice) + " §7> ceiling §f" + compact(config.buyCeiling) + "§7. Hold Shift to override.");
                return InteractionResult.FAIL;
            }
            if (sell && config.sellFloor > 0 && stockPrice < config.sellFloor) {
                local("Sell blocked: §f" + compact(stockPrice) + " §7< floor §f" + compact(config.sellFloor) + "§7. Hold Shift to override.");
                return InteractionResult.FAIL;
            }
            if (sell) suppressLpsFor(3000);
            return InteractionResult.PASS;
        });

        registerCommands();
    }

    @Override public void onUnload() {
        loaded = false;
        treecuttersDetected = false;
        if (actionBarListener != null) {
            dev.intensetomato.dfu.api.DfuEvents.offActionBar(actionBarListener);
            actionBarListener = null;
        }
        if (INSTANCE == this) INSTANCE = null;
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> d.register(literal("tc")
            .executes(c -> { openConfigNextTick = true; return 1; })
            .then(literal("hud").executes(c -> { openHudEditorNextTick = true; return 1; }))
            .then(literal("stock")
                .then(literal("buyceiling").then(argument("value", DoubleArgumentType.doubleArg(0)).executes(c -> { config.buyCeiling=DoubleArgumentType.getDouble(c,"value"); saveConfig(); local("Buy ceiling: §f"+compact(config.buyCeiling)); return 1; })))
                .then(literal("sellfloor").then(argument("value", DoubleArgumentType.doubleArg(0)).executes(c -> { config.sellFloor=DoubleArgumentType.getDouble(c,"value"); saveConfig(); local("Sell floor: §f"+compact(config.sellFloor)); return 1; })))
                .then(literal("info").executes(c -> { stockInfo(); return 1; })))
            .then(literal("goal").then(argument("logs", DoubleArgumentType.doubleArg(0)).executes(c -> { config.goal=DoubleArgumentType.getDouble(c,"logs"); saveConfig(); local("Goal: §f"+compact(config.goal)); return 1; })))
            .then(literal("stats").executes(c -> { local("Session +§f"+compact(sessionGain)+" §7| peak §f"+compact(peakLps)+"/s §7| " + formatDuration(System.currentTimeMillis()-sessionStartMs)); return 1; }))
        ));
    }


    private void suppressLpsFor(long millis) {
        suppressLpsUntilMs = Math.max(suppressLpsUntilMs, System.currentTimeMillis() + millis);
    }

    private void parseActionbar(String msg) {
        if (!loaded) return;
        Matcher m = ACTION_LOGS.matcher(msg);
        if (m.find()) {
            logs = parseCompact(m.group(1));
            treecuttersDetected = true;
            lastTreecuttersActionbar = System.currentTimeMillis();
            if (!config.welcomed) {
                config.welcomed = true;
                saveConfig();
                local("Treecutters Utilities is ready. Run §f/tc §7to configure it.");
            }
        }
        m = ACTION_STOCK.matcher(msg); if (m.find()) stockPrice = parseCompact(m.group(1));
        m = ACTION_LPS.matcher(msg); if (m.find()) {
            double rate = parseCompact(m.group(1));
            if (rate >= 0) {
                long now = System.currentTimeMillis();
                if (now >= suppressLpsUntilMs) {
                    displayedLps = rate;
                    lpsSamples.addLast(new Sample(now, rate));
                    peakLps = Math.max(peakLps, rate);
                    trimLpsSamples();
                }
            }
        }
    }

    private void parseTabHeader(Minecraft mc) {
        try {
            Component h = ((PlayerTabOverlayAccessor)(Object)mc.gui.getTabList()).dfu$getHeader();
            if (h == null) return;
            String text = h.getString();
            int nextPlayers=playersOnline;
            String nextWeather=weather;
            Matcher m=PLAYERS.matcher(text); if(m.find()) nextPlayers=Integer.parseInt(m.group(1));
            m=WEATHER_STATUS.matcher(text); if(m.find()) nextWeather=m.group(1);
            m=WEATHER_COST.matcher(text); if(m.find()) weatherCostPerPlayer=parseCompact(m.group(1));
            m=WEATHER_BOOST.matcher(text); if(m.find()) weatherBoost=Double.parseDouble(m.group(1))/100.0;
            m=WEATHER_LOGS.matcher(text); double last=-1; while(m.find()) last=parseCompact(m.group(1));
            if(last>=0){
                long now=System.currentTimeMillis();
                double predicted=projectedWeatherLogs();
                double tolerance=Math.max(1,weatherDrain(nextWeather)*Math.max(1,nextPlayers)*2.25);
                if(weatherLogs<0 || !Objects.equals(nextWeather,weather) || nextPlayers!=playersOnline || Math.abs(last-predicted)>tolerance){
                    weatherLogs=last;
                    weatherSyncMs=now;
                }
            }
            playersOnline=nextPlayers;
            weather=nextWeather;
        } catch (Throwable ignored) {}
    }

    private void scanStockSigns(Minecraft mc) {
        if (mc.level == null || mc.player == null) return;
        BlockPos center = mc.player.blockPosition();
        for (int dx=-10; dx<=10; dx++) for (int dy=-5; dy<=5; dy++) for (int dz=-10; dz<=10; dz++) {
            BlockEntity be = mc.level.getBlockEntity(center.offset(dx,dy,dz));
            if (be instanceof SignBlockEntity sign) {
                for (Component c : sign.getFrontText().getMessages(false)) {
                    Matcher m=STOCKS_SIGN.matcher(c.getString());
                    if(m.find()) { stocks=parseCompact(m.group(1)); return; }
                }
            }
        }
    }

    private void trimLpsSamples() {
        long cutoff=System.currentTimeMillis()-60_000L;
        while(!lpsSamples.isEmpty() && lpsSamples.peekFirst().time<cutoff) lpsSamples.removeFirst();
    }

    private double lps(int seconds) {
        long cutoff=System.currentTimeMillis()-seconds*1000L; double sum=0; int count=0;
        for(Sample s:lpsSamples) if(s.time>=cutoff){ sum+=s.gain; count++; }
        return count==0 ? (displayedLps>=0?displayedLps:0) : sum/count;
    }

    private double projectedWeatherLogs() {
        if(weatherLogs<0) return -1;
        double drain=weatherDrain(weather)*Math.max(0,playersOnline);
        if(drain<=0 || weatherSyncMs<0) return weatherLogs;
        double elapsed=Math.max(0,(System.currentTimeMillis()-weatherSyncMs)/1000.0);
        return Math.max(0, weatherLogs-drain*elapsed);
    }

    private void renderHud(GuiGraphics g) {
        Minecraft mc=Minecraft.getInstance(); if(mc.player==null || mc.options.hideGui || hudHidden || !treecuttersDetected || System.currentTimeMillis()-lastTreecuttersActionbar>15000) return;

        if(config.lps.enabled) {
            List<String> lines = displayedLps < 0
                ? List.of("Waiting for action bar...")
                : List.of("1s  "+compact(displayedLps)+"/s","10s "+compact(lps(10))+"/s","60s "+compact(lps(60))+"/s","Peak "+compact(peakLps)+"/s");
            panel(g,config.lps,"LPS",lines);
        }

        if(config.weather.enabled) {
            List<String> lines=new ArrayList<>();
            if(weather.equals("Unknown")) lines.add("Waiting for scoreboard...");
            else {
                double threshold=weatherThreshold(weather), drain=weatherDrain(weather)*Math.max(0,playersOnline), projected=projectedWeatherLogs();
                double seconds=(drain>0&&projected>threshold)?(projected-threshold)/drain:0;
                lines.add(weather+" • "+(playersOnline>=0?playersOnline+"p":"?p"));
                if(drain>0){ lines.add("Drain "+compact(drain)+"/s"); lines.add(projected>=0?"Tier left "+formatSeconds(seconds):"Tier left --"); lines.add("+100M = "+formatSeconds(100_000_000d/drain)); }
                if(config.weatherWarnings && (weather.equals("Tornado") || weather.equals("Supercell") || weather.equals("Multi-Vortex Supercell") || weather.equals("EF-6 Multi-Vortex Supercell")) && seconds>0 && seconds<=30 && ticks%20<10) g.drawString(mc.font,weather.toUpperCase(Locale.ROOT)+" < "+(int)Math.ceil(seconds)+"s",mc.getWindow().getGuiScaledWidth()/2-40,20,0xFFFF5555,true);
            }
            panel(g,config.weather,"Weather",lines);
        }

        if(config.stocks.enabled) {
            List<String> lines=new ArrayList<>();
            lines.add(stockPrice>0?"Price "+compact(stockPrice):"Price --");
            lines.add(stocks>=0?"Shares "+compact(stocks):"Shares -- (look at sign)");
            if(stockPrice>0&&stocks>=0){
                double liq=liquidation(stocks,stockPrice,6000,100000);
                lines.add("Sell-all ~"+compact(liq));
                if(config.sellFloor>0) lines.add("To floor ~"+compact(sellableBeforeFloor(stocks,stockPrice,config.sellFloor,6000))+" shares");
            } else lines.add("Sell-all --");
            panel(g,config.stocks,"Stocks",lines);
        }

        if(config.goalPanel.enabled) {
            if(config.goal<=0) panel(g,config.goalPanel,"Goal",List.of("No goal set","/tc goal <logs>"));
            else if(logs<0) panel(g,config.goalPanel,"Goal",List.of("Waiting for balance..."));
            else {
                double rem=Math.max(0,config.goal-logs), rate=Math.max(lps(10),lps(60));
                panel(g,config.goalPanel,"Goal",List.of(compact(logs)+" / "+compact(config.goal), rem<=0?"READY":"Left "+compact(rem), rate>0?"ETA "+formatSeconds(rem/rate):"ETA --"));
            }
        }

        if(config.events.enabled) panel(g,config.events,"Stock Events",stockEvents.isEmpty()?List.of("No events recorded"):new ArrayList<>(stockEvents));



        if(config.session.enabled) {
            long elapsed=System.currentTimeMillis()-sessionStartMs;
            panel(g,config.session,"Session",List.of("Time "+formatDuration(elapsed),"Peak "+compact(peakLps)+"/s","Current "+(displayedLps>=0?compact(displayedLps)+"/s":"--")));
        }
    }

    private void panel(GuiGraphics g, Panel p, String title, List<String> sourceLines) {
        Minecraft mc=Minecraft.getInstance();
        List<String> lines=new ArrayList<>();
        for(int i=0;i<sourceLines.size();i++) if(p.line(i)) lines.add(sourceLines.get(i));
        if(!p.showHeader && lines.isEmpty()) return;
        float scale=Math.max(0.65f,Math.min(2f,p.scale));
        int screenW=mc.getWindow().getGuiScaledWidth(),screenH=mc.getWindow().getGuiScaledHeight();
        int logicalMax=Math.max(72,(int)((screenW-p.x-4)/scale));
        String shownTitle=fitHud(mc,title,logicalMax-10);
        List<String> shownLines=new ArrayList<>();
        for(String line:lines) shownLines.add(fitHud(mc,line,logicalMax-10));
        int logicalW=72;
        if(p.showHeader) logicalW=Math.max(logicalW,mc.font.width(shownTitle)+10);
        for(String line:shownLines) logicalW=Math.max(logicalW,mc.font.width(line)+10);
        int top=p.showHeader?16:5,logicalH=Math.max(12,top+shownLines.size()*10+2);
        int drawW=Math.round(logicalW*scale),drawH=Math.round(logicalH*scale);
        int x=Math.max(0,Math.min(p.x,Math.max(0,screenW-drawW))),y0=Math.max(0,Math.min(p.y,Math.max(0,screenH-drawH)));
        p.x=x;p.y=y0;
        g.pose().pushMatrix();
        g.pose().translate(x,y0);
        g.pose().scale(scale,scale);
        int alpha=Math.max(0,Math.min(255,p.opacity));
        if(alpha>0) g.fill(0,0,logicalW,logicalH,(alpha<<24)|0x101713);
        if(p.showHeader){g.fill(0,0,logicalW,1,0xFF55D67A);g.drawString(mc.font,shownTitle,5,5,0xFFAAFFB7,p.shadow);}
        int y=top;
        for(String line:shownLines){g.drawString(mc.font,line,5,y,0xFFE6F2E9,p.shadow);y+=10;}
        g.pose().popMatrix();
    }

    private static String fitHud(Minecraft mc,String value,int maxWidth){if(maxWidth<=8||mc.font.width(value)<=maxWidth)return value;String e="…";return mc.font.plainSubstrByWidth(value,Math.max(0,maxWidth-mc.font.width(e)))+e;}

    private void renderUpgradeHint(Screen screen, GuiGraphics g) {
        if(!(screen instanceof AbstractContainerScreen<?> cs)) return;
        UpgradeChoice best=null;
        for(Slot slot:cs.getMenu().slots) {
            ItemStack st=slot.getItem(); if(st.isEmpty()) continue;
            String name=st.getHoverName().getString();
            if(!UPGRADE_NAMES.contains(name)) continue;
            try {
                List<Component> tt=st.getTooltipLines(Item.TooltipContext.of(Minecraft.getInstance().level),Minecraft.getInstance().player,TooltipFlag.NORMAL);
                double cost=-1; int level=-1,max=-1;
                for(Component c:tt){String s=c.getString();Matcher cm=COST_LORE.matcher(s);if(cm.find())cost=parseCompact(cm.group(1));Matcher lm=LEVEL_LORE.matcher(s);if(lm.find()){level=Integer.parseInt(lm.group(1).replace(",",""));max=Integer.parseInt(lm.group(2).replace(",",""));}}
                if(level>=0){observedUpgrades.put(name,new UpgradeSnapshot(name,level,max,cost));if(name.equals("Tree Type"))observedTreeLevel=level;if(name.equals("Fortune"))observedFortuneLevel=level;}
                if(!config.upgradeOptimizer||cost<=0||level<0||level>=max)continue;
                double score=upgradeBenefit(name,level)/cost;
                if(best==null||score>best.score)best=new UpgradeChoice(slot,score,name);
            } catch(Throwable ignored){}
        }
        if(config.upgradeOptimizer&&best!=null){AbstractContainerScreenAccessor pos=(AbstractContainerScreenAccessor)(Object)cs;int x=pos.treecuttersUtilities$getLeftPos()+best.slot.x,y=pos.treecuttersUtilities$getTopPos()+best.slot.y;g.fill(x,y,x+16,y+16,0x7830E85A);g.fill(x,y,x+16,y+1,0xFF72FF8D);g.fill(x,y+15,x+16,y+16,0xFF72FF8D);g.fill(x,y,x+1,y+16,0xFF72FF8D);g.fill(x+15,y,x+16,y+16,0xFF72FF8D);}
    }


    private static final Set<String> UPGRADE_NAMES=Set.of("Sweep","Fortune","Tree Growth Speed","Jump Height","Tree Type","Walk Speed","Throwing Axe Cooldown","Swing Range","Turret","Cut-off Rate","Meowltishot","Timber","Extended Jumps","Throwing Axe Speed","Combo Tracker");
    private double upgradeBenefit(String n,int l){return switch(n){case "Fortune"->0.08;case "Tree Growth Speed"->1.0/Math.max(1,l+1);case "Turret"->{double old=80.0-Math.max(0,l),neu=79.0-Math.max(0,l);yield Math.max(0.001,old/neu-1);}case "Sweep"->0.015;case "Cut-off Rate"->0.01;case "Timber"->0.0025;case "Meowltishot"->0.05;case "Throwing Axe Cooldown","Throwing Axe Speed"->0.02;default->0.001;};}

    private void onMessage(String msg) {
        if(TICKER.matcher(msg).matches()){stockEvents.addFirst(msg.length()>42?msg.substring(0,42)+"…":msg);while(stockEvents.size()>5)stockEvents.removeLast();}
    }

    private static void local(String s){Minecraft mc=Minecraft.getInstance();if(mc.player!=null)mc.player.displayClientMessage(Component.literal(PREFIX+s),false);}
    private void stockInfo(){if(stockPrice<0||stocks<0){local("Stock data unavailable. Visit the stock area.");return;}local("§f"+compact(stocks)+" §7shares @ §f"+compact(stockPrice)+" §7| sell-all ~§f"+compact(liquidation(stocks,stockPrice,6000,100000)));}

    private static double liquidation(double shares,double price,double drop,double floor){long n=(long)Math.floor(shares);double total=0,p=price;for(long i=0;i<n;i++){total+=p;p=Math.max(floor,p-drop);}return total;}
    private static double sellableBeforeFloor(double shares,double price,double floor,double drop){if(price<floor)return 0;return Math.min(shares,Math.floor((price-floor)/drop)+1);}
    private static boolean near(BlockPos p,int x,int y,int z,int r){return Math.abs(p.getX()-x)<=r&&Math.abs(p.getY()-y)<=r&&Math.abs(p.getZ()-z)<=r;}
    private static String weatherBoostText(String w){return switch(w){case "Rain"->"+20%";case "Thunderstorm"->"+50%";case "Severe Thunderstorm"->"+90%";case "Hurricane"->"+150%";case "Tornado"->"+250%";case "Supercell"->"+350%";case "Multi-Vortex Supercell"->"+500%";case "EF-6 Multi-Vortex Supercell"->"+700%";default->"--";};}
    private static double weatherThreshold(String w){return switch(w){case "EF-6 Multi-Vortex Supercell"->3e9;case "Multi-Vortex Supercell"->1e9;case "Supercell"->500e6;case "Tornado"->400e6;case "Hurricane"->250e6;case "Severe Thunderstorm"->100e6;case "Thunderstorm"->50e6;case "Rain"->10e6;default->0;};}
    private static double weatherDrain(String w){return switch(w){case "EF-6 Multi-Vortex Supercell"->1.1e6;case "Multi-Vortex Supercell"->800e3;case "Supercell"->400e3;case "Tornado"->300e3;case "Hurricane"->150e3;case "Severe Thunderstorm"->40e3;case "Thunderstorm"->15e3;case "Rain"->5e3;default->0;};}

    private static double parseCompact(String s){s=s.replace(",","").trim();if(s.isEmpty())return -1;char c=s.charAt(s.length()-1);double mult=switch(Character.toUpperCase(c)){case 'K'->1e3;case 'M'->1e6;case 'B'->1e9;case 'T'->1e12;case 'Q'->1e15;default->1;};if(mult!=1)s=s.substring(0,s.length()-1);try{return Double.parseDouble(s)*mult;}catch(Exception e){return -1;}}
    private static String compact(double v){double a=Math.abs(v);String[]u={"","K","M","B","T","Q"};int i=0;while(a>=1000&&i<u.length-1){a/=1000;v/=1000;i++;}return String.format(Locale.US,Math.abs(v)>=100?"%.0f%s":Math.abs(v)>=10?"%.1f%s":"%.2f%s",v,u[i]);}
    private static String formatSeconds(double s){if(!Double.isFinite(s)||s<0)return "--";long n=(long)s;return n>=3600?(n/3600)+"h "+((n%3600)/60)+"m":n>=60?(n/60)+"m "+(n%60)+"s":n+"s";}
    private static String formatDuration(long ms){return formatSeconds(ms/1000.0);}

    private Config loadConfig(){try{if(Files.exists(CONFIG_PATH)){Config c=GSON.fromJson(Files.readString(CONFIG_PATH),Config.class);if(c!=null){c.fix();return c;}}}catch(Exception ignored){}Config c=new Config();saveConfig(c);return c;}
    private void saveConfig(){saveConfig(config);} private static void saveConfig(Config c){try{Files.createDirectories(CONFIG_PATH.getParent());Files.writeString(CONFIG_PATH,GSON.toJson(c));}catch(IOException ignored){}}

    private static final class ConfigScreen extends Screen {
        private static final Identifier BACKGROUND=Identifier.fromNamespaceAndPath("diamondfire_utilities","textures/gui/treecutters_background.png");
        private final Screen parent;
        private Tab tab=Tab.HOME;
        private int hudSelected;
        ConfigScreen(Screen parent){super(Component.literal("Treecutters Utilities"));this.parent=parent;}
        private double uiScale(){return Math.min(1.0,Math.min((width-20)/720.0,(height-20)/400.0));} private int u(int n){return n;} private int boxW(){return 720;} private int boxH(){return 400;} private int left(){return 0;} private int top(){return 0;} private int originX(){return(int)Math.round((width-boxW()*uiScale())/2.0);} private int originY(){return(int)Math.round((height-boxH()*uiScale())/2.0);} private int localX(double x){return(int)Math.floor((x-originX())/uiScale());} private int localY(double y){return(int)Math.floor((y-originY())/uiScale());}
        @Override protected void init(){clearWidgets();}
        private void applyPreset(String preset){boolean minimal=preset.equals("minimal"),grinding=preset.equals("grinding");INSTANCE.config.lps.enabled=true;INSTANCE.config.weather.enabled=true;INSTANCE.config.stocks.enabled=!minimal&&!grinding;INSTANCE.config.goalPanel.enabled=!minimal;INSTANCE.config.events.enabled=false;INSTANCE.config.session.enabled=grinding;if(minimal){INSTANCE.config.lps.x=8;INSTANCE.config.lps.y=8;INSTANCE.config.weather.x=8;INSTANCE.config.weather.y=70;}if(grinding){INSTANCE.config.lps.x=8;INSTANCE.config.lps.y=8;INSTANCE.config.weather.x=8;INSTANCE.config.weather.y=70;INSTANCE.config.goalPanel.x=135;INSTANCE.config.goalPanel.y=8;INSTANCE.config.session.x=270;INSTANCE.config.session.y=8;}INSTANCE.saveConfig();}
        @Override public void render(GuiGraphics g,int mx,int my,float d){
            g.fill(0,0,width,height,0x58000000);double scale=uiScale();int ox=originX(),oy=originY(),lmx=localX(mx),lmy=localY(my);g.pose().pushMatrix();g.pose().translate(ox,oy);g.pose().scale((float)scale,(float)scale);int l=0,t=0,r=boxW(),b=boxH();frame(g,l,t,r,b);g.fill(l+u(5),t+u(5),r-u(5),t+u(51),0xE0121A17);bevel(g,l+u(5),t+u(5),r-u(5),t+u(51));
            g.renderItem(new ItemStack(Items.IRON_AXE),l+u(18),t+u(19));g.drawString(font,"Treecutters Utilities",l+u(43),t+u(14),0xFFB9FFD0,true);g.drawString(font,INSTANCE.isActive()?"Treecutting detected":"Waiting for Treecutting",l+u(43),t+u(31),INSTANCE.isActive()?0xFF8CFF9A:0xFFB9B9B9,false);
            int sx=l+u(10),sy=t+u(61),sw=u(130),ch=boxH()-u(103);g.fill(sx,sy,sx+sw,sy+ch,0xE6151C20);bevel(g,sx,sy,sx+sw,sy+ch);drawNav(g,sx+u(6),sy+u(7),sw-u(12),lmx,lmy);
            int x=sx+sw+u(10),y=sy,w=r-x-u(10),h=ch;if(tab==Tab.HOME){imageCover(g,BACKGROUND,x,y,w,h,2048,1084);g.fill(x,y,x+w,y+h,0x58080D11);}else{g.fill(x,y,x+w,y+h,0xF01A2228);}bevel(g,x,y,x+w,y+h);
            int cx=x+u(12),cy=y+u(12),cw=w-u(24);switch(tab){case HOME->home(g,cx,cy,cw,h-u(24),lmx,lmy);case HUD->hud(g,cx,cy,cw,lmx,lmy);case UPGRADES->upgradeInfo(g,cx,cy,cw);case TRINKETS->trinkets(g,cx,cy,cw);case WEATHER->weather(g,cx,cy,cw);case STOCKS->stocks(g,cx,cy,cw);case SESSION->session(g,cx,cy,cw);}
            int dw=u(110),dh=Math.max(18,u(22));button(g,(l+r)/2-dw/2,b-u(34),dw,dh,"Done",inside(lmx,lmy,(l+r)/2-dw/2,b-u(34),dw,dh));g.pose().popMatrix();super.render(g,mx,my,d);
        }
        private void imageCover(GuiGraphics g,Identifier texture,int x,int y,int w,int h,int tw,int th){double target=(double)w/h,image=(double)tw/th;int su=0,sv=0,sw=tw,sh=th;if(image>target){sw=(int)Math.round(th*target);su=(tw-sw)/2;}else{sh=(int)Math.round(tw/target);sv=(th-sh)/2;}g.blit(RenderPipelines.GUI_TEXTURED,texture,x,y,(float)su,(float)sv,w,h,sw,sh,tw,th);}
        private void drawNav(GuiGraphics g,int x,int y,int w,int mx,int my){int yy=y,rh=Math.max(22,u(27)),step=Math.max(27,u(34));for(Tab v:Tab.values()){boolean a=tab==v,h=inside(mx,my,x,yy,w,rh);g.fill(x,yy,x+w,yy+rh,a?0xFF344553:h?0xFF303A43:0xFF232A30);bevel(g,x,yy,x+w,yy+rh);g.renderItem(new ItemStack(v.icon),x+u(6),yy+Math.max(3,(rh-16)/2));g.drawString(font,fit(v.label,Math.max(20,w-u(31))),x+u(27),yy+(rh-font.lineHeight)/2,a?0xFFB9F5FF:0xFFFFFFFF,true);yy+=step;}}
        private void home(GuiGraphics g,int x,int y,int w,int h,int mx,int my){int center=x+w/2;g.renderItem(new ItemStack(Items.IRON_AXE),center-8,y+18);g.drawCenteredString(font,"Treecutters Utilities",center,y+42,0xFFB9FFD0);g.drawCenteredString(font,"Treecutting",center,y+57,0xFFE7ECEF);int cardY=y+82,cardH=54,gap=7,cardW=(w-gap)/2;glassBox(g,x,cardY,cardW,cardH);glassBox(g,x+cardW+gap,cardY,cardW,cardH);g.drawString(font,"Logs",x+10,cardY+10,0xFFB7C2CA,false);String logsText=INSTANCE.logs>=0?compact(INSTANCE.logs):"--";g.drawString(font,logsText,x+10,cardY+27,0xFFFFFFFF,true);g.drawString(font,"LPS",x+cardW+gap+10,cardY+10,0xFFB7C2CA,false);String lpsText=INSTANCE.displayedLps>=0?compact(INSTANCE.displayedLps)+"/s":"--";g.drawString(font,lpsText,x+cardW+gap+10,cardY+27,0xFF55E98A,true);int by=cardY+cardH+10,bw=(w-gap*2)/3;quick(g,x,by,bw,"HUD",Items.COMPASS,mx,my);quick(g,x+bw+gap,by,bw,"Upgrades",Items.ENCHANTED_BOOK,mx,my);quick(g,x+(bw+gap)*2,by,bw,"Stocks",Items.GOLD_INGOT,mx,my);int wy=by+54;glassBox(g,x,wy,w,48);g.drawString(font,"Weather",x+10,wy+9,0xFFB7C2CA,false);String weatherText=fit(INSTANCE.weather,w-100);g.drawString(font,weatherText,x+10,wy+25,0xFFFFFFFF,true);String boost=INSTANCE.weatherBoost>=0?"+"+String.format(Locale.US,"%.0f",INSTANCE.weatherBoost)+"%":weatherBoostText(INSTANCE.weather);g.drawString(font,boost,x+w-font.width(boost)-10,wy+25,0xFF55E98A,true);}
        private void glassBox(GuiGraphics g,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xB8182026);bevel(g,x,y,x+w,y+h);}
        private void hud(GuiGraphics g,int x,int y,int w,int mx,int my){
            title(g,"HUD",x,y);
            String[] names={"LPS","Weather","Stocks","Goal","Stock Events","Session"};
            Panel[] panels={INSTANCE.config.lps,INSTANCE.config.weather,INSTANCE.config.stocks,INSTANCE.config.goalPanel,INSTANCE.config.events,INSTANCE.config.session};
            int gap=6,cw=(w-gap)/2;
            for(int i=0;i<names.length;i++){
                int bx=x+(i%2)*(cw+gap),by=y+25+(i/2)*34;
                boolean selected=i==hudSelected,hover=inside(mx,my,bx,by,cw,28);
                g.fill(bx,by,bx+cw,by+28,selected?0xFF344B5A:hover?0xFF303A43:0xFF222A30);
                bevel(g,bx,by,bx+cw,by+28);
                if(selected){g.fill(bx,by,bx+2,by+28,0xFF74E9FF);g.fill(bx,by,bx+cw,by+2,0xFF74E9FF);}
                g.drawString(font,names[i],bx+8,by+10,0xFFF2F4F6,true);
                String state=panels[i].enabled?"Shown":"Hidden";
                g.drawString(font,state,bx+cw-font.width(state)-8,by+10,panels[i].enabled?0xFF55E98A:0xFF89939B,false);
            }
            Panel p=panels[Math.max(0,Math.min(hudSelected,panels.length-1))];
            String name=names[Math.max(0,Math.min(hudSelected,names.length-1))];
            int dy=y+134;
            g.drawString(font,name+" settings",x,dy,0xFFF2F4F6,true);
            g.fill(x,dy+15,x+Math.min(135,font.width(name+" settings")+18),dy+17,0xFF344553);
            int by=dy+24,bw=(w-18)/4;
            hudButton(g,x,by,bw,20,p.enabled?"Shown":"Hidden",mx,my);
            hudButton(g,x+bw+6,by,bw,20,p.showHeader?"Header on":"Header off",mx,my);
            hudButton(g,x+(bw+6)*2,by,bw,20,p.shadow?"Shadow on":"Shadow off",mx,my);
            hudButton(g,x+(bw+6)*3,by,bw,20,"Reset",mx,my);
            by+=27;
            int half=(w-6)/2;
            box(g,x,by,half,22);
            g.drawString(font,"Scale",x+7,by+8,0xFF9FAAB3,false);
            String scale=Math.round(p.scale*100)+"%";g.drawCenteredString(font,scale,x+half/2,by+8,0xFFFFFFFF);
            g.drawString(font,"-",x+half-39,by+8,0xFF72E7F8,true);g.drawString(font,"+",x+half-16,by+8,0xFF72E7F8,true);
            box(g,x+half+6,by,half,22);
            g.drawString(font,"Opacity",x+half+13,by+8,0xFF9FAAB3,false);
            String op=Math.round(p.opacity/255f*100)+"%";g.drawCenteredString(font,op,x+half+6+half/2,by+8,0xFFFFFFFF);
            g.drawString(font,"-",x+w-39,by+8,0xFF72E7F8,true);g.drawString(font,"+",x+w-16,by+8,0xFF72E7F8,true);
            by+=29;
            String[] lines=lineLabels(name);int lw=(w-12)/3;
            for(int i=0;i<Math.min(6,lines.length);i++){int lx=x+(i%3)*(lw+6),ly=by+(i/3)*24;hudButton(g,lx,ly,lw,20,(p.line(i)?"✓ ":"× ")+lines[i],mx,my);}
            int py=by+(lines.length>3?50:26);
            g.drawString(font,"Position  "+p.x+", "+p.y,x,py+7,0xFFB5C0C8,false);
            int px=x+w-236;hudButton(g,px,py,38,20,"X-",mx,my);hudButton(g,px+42,py,38,20,"X+",mx,my);hudButton(g,px+84,py,38,20,"Y-",mx,my);hudButton(g,px+126,py,38,20,"Y+",mx,my);hudButton(g,px+168,py,68,20,"Arrange",mx,my);
        }
        private void hudButton(GuiGraphics g,int x,int y,int w,int h,String text,int mx,int my){button(g,x,y,w,h,fit(text,w-8),inside(mx,my,x,y,w,h));}
        private Panel hudPanel(){return switch(hudSelected){case 1->INSTANCE.config.weather;case 2->INSTANCE.config.stocks;case 3->INSTANCE.config.goalPanel;case 4->INSTANCE.config.events;case 5->INSTANCE.config.session;default->INSTANCE.config.lps;};}
        private String hudName(){return switch(hudSelected){case 1->"Weather";case 2->"Stocks";case 3->"Goal";case 4->"Stock Events";case 5->"Session";default->"LPS";};}
        private void upgradeInfo(GuiGraphics g,int x,int y,int w){title(g,"Upgrade Info",x,y);g.drawString(font,"Open the Treecutting upgrade menu once to refresh values.",x,y+16,0xFF9FAAB3,false);int yy=y+35;List<UpgradeSnapshot> rows=new ArrayList<>(INSTANCE.observedUpgrades.values());rows.sort(Comparator.comparing(UpgradeSnapshot::name));double total=0;for(UpgradeSnapshot u:rows)total+=remaining(u);String totalText=rows.isEmpty()?"--":compact(total);g.drawString(font,"Total left",x,y+36,0xFF9FAAB3,false);g.drawString(font,totalText,x+w-font.width(totalText),y+36,0xFF55E98A,true);yy=y+57;g.fill(x,yy,x+w,yy+18,0xFF313A42);g.drawString(font,"Upgrade",x+7,yy+5,0xFFFFFFFF,true);g.drawString(font,"Level",x+w/2-25,yy+5,0xFFFFFFFF,true);g.drawString(font,"Left",x+w-70,yy+5,0xFFFFFFFF,true);yy+=22;int shown=0;for(UpgradeSnapshot u:rows){if(shown>=8||yy+22>top()+boxH()-58)break;g.fill(x,yy,x+w,yy+20,(shown&1)==0?0xFF242B31:0xFF1D2328);String nm=fit(u.name(),w/2-20);g.drawString(font,nm,x+7,yy+6,0xFFF2F4F6,false);String lv=u.level()+"/"+u.max();g.drawString(font,lv,x+w/2-25,yy+6,0xFFD4DCE3,false);String rem=u.level()>=u.max()?"MAX":compact(remaining(u));g.drawString(font,rem,x+w-font.width(rem)-8,yy+6,u.level()>=u.max()?0xFF55FF88:0xFFF2F4F6,false);yy+=22;shown++;}if(rows.isEmpty())g.drawCenteredString(font,"No upgrade data yet",x+w/2,yy+24,0xFF89939B);}
        private void trinkets(GuiGraphics g,int x,int y,int w){title(g,"Trinkets",x,y);String[][] a={{"Helix","65L / 5G","+4 Sweep, +0.5% Timber"},{"Absolution","30L / 60G","+1 Timber"},{"Ovation","8L / 6G","+2 Sweep, +0.5% Timber"},{"Atom","4L / 2G","+1 Sweep, +1 Multishot"},{"Fechten","3L / 5G","+3 Sweep, +20 Walk"},{"Lil' Goob","2L / 2G","-4% Cut-off"},{"Sierpinski","2L / 1G","+6 Sweep"},{"Annihilation","2L / 10G","+1 Timber"},{"Transmutation","3L / 6G","+3 Sweep"},{"Sharpness","7L / 2G","No secondary stat"}};int gap=6,cw=(w-gap)/2;for(int i=0;i<a.length;i++){int bx=x+(i%2)*(cw+gap),by=y+27+(i/2)*38;box(g,bx,by,cw,32);g.drawString(font,a[i][0],bx+7,by+6,0xFFF2F4F6,true);g.drawString(font,a[i][1],bx+cw-font.width(a[i][1])-7,by+6,0xFF55E98A,false);g.drawString(font,fit(a[i][2],cw-14),bx+7,by+19,0xFF9FAAB3,false);}}
        private void weather(GuiGraphics g,int x,int y,int w){title(g,"Weather",x,y);row(g,"Current",INSTANCE.weather,x,y+30,w);row(g,"Boost",INSTANCE.weatherBoost>=0?"+"+String.format(Locale.US,"%.0f",INSTANCE.weatherBoost)+"%":weatherBoostText(INSTANCE.weather),x,y+62,w);row(g,"Players",INSTANCE.playersOnline>=0?Integer.toString(INSTANCE.playersOnline):"--",x,y+94,w);row(g,"Stored",INSTANCE.weatherLogs>=0?compact(INSTANCE.projectedWeatherLogs()):"--",x,y+126,w);row(g,"Drain",compact(weatherDrain(INSTANCE.weather)*Math.max(0,INSTANCE.playersOnline))+"/s",x,y+158,w);}
        private void stocks(GuiGraphics g,int x,int y,int w){title(g,"Stocks",x,y);row(g,"Price",INSTANCE.stockPrice>=0?compact(INSTANCE.stockPrice):"--",x,y+30,w);row(g,"Shares",INSTANCE.stocks>=0?compact(INSTANCE.stocks):"--",x,y+62,w);row(g,"Liquidation",INSTANCE.stockPrice>0&&INSTANCE.stocks>=0?compact(liquidation(INSTANCE.stocks,INSTANCE.stockPrice,6000,100000)):"--",x,y+94,w);row(g,"Buy ceiling",INSTANCE.config.buyCeiling>0?compact(INSTANCE.config.buyCeiling):"Off",x,y+126,w);row(g,"Sell floor",INSTANCE.config.sellFloor>0?compact(INSTANCE.config.sellFloor):"Off",x,y+158,w);}
        private void session(GuiGraphics g,int x,int y,int w){title(g,"Session",x,y);long elapsed=System.currentTimeMillis()-INSTANCE.sessionStartMs;row(g,"Time",formatDuration(elapsed),x,y+30,w);row(g,"Logs gained",compact(INSTANCE.sessionGain),x,y+62,w);row(g,"Current",INSTANCE.displayedLps>=0?compact(INSTANCE.displayedLps)+"/s":"--",x,y+94,w);row(g,"10s average",compact(INSTANCE.lps(10))+"/s",x,y+126,w);row(g,"Peak",compact(INSTANCE.peakLps)+"/s",x,y+158,w);}
        @Override public boolean mouseClicked(MouseButtonEvent e,boolean dbl){if(e.button()!=0)return super.mouseClicked(e,dbl);int l=0,t=0,r=boxW(),b=boxH(),mx=localX(e.x()),my=localY(e.y()),sx=l+u(16),sy=t+u(68),sw=u(118),yy=sy,rh=Math.max(22,u(27)),step=Math.max(27,u(34));for(Tab v:Tab.values()){if(inside(mx,my,sx,yy,sw,rh)){tab=v;return true;}yy+=step;}int x=l+u(10)+u(130)+u(10)+u(12),y=t+u(61)+u(12),w=r-u(10)-u(12)-x;if(tab==Tab.HOME){int gap=7,bw=(w-gap*2)/3,by=y+146;if(inside(mx,my,x,by,bw,46)){tab=Tab.HUD;return true;}if(inside(mx,my,x+bw+gap,by,bw,46)){tab=Tab.UPGRADES;return true;}if(inside(mx,my,x+(bw+gap)*2,by,bw,46)){tab=Tab.STOCKS;return true;}}if(tab==Tab.HUD){
                int gap=6,cw=(w-gap)/2;
                for(int i=0;i<6;i++){int bx=x+(i%2)*(cw+gap),by=y+25+(i/2)*34;if(inside(mx,my,bx,by,cw,28)){hudSelected=i;return true;}}
                Panel p=hudPanel();String name=hudName();int dy=y+134,by=dy+24,bw=(w-18)/4;
                if(inside(mx,my,x,by,bw,20)){p.enabled=!p.enabled;INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+bw+6,by,bw,20)){p.showHeader=!p.showHeader;INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+(bw+6)*2,by,bw,20)){p.shadow=!p.shadow;INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+(bw+6)*3,by,bw,20)){resetPanel(p,name);INSTANCE.saveConfig();return true;}
                by+=27;int half=(w-6)/2;
                if(inside(mx,my,x+half-50,by,28,22)){p.scale=Math.max(.65f,p.scale-.1f);INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+half-27,by,27,22)){p.scale=Math.min(2f,p.scale+.1f);INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+w-50,by,28,22)){p.opacity=Math.max(0,p.opacity-20);INSTANCE.saveConfig();return true;}
                if(inside(mx,my,x+w-27,by,27,22)){p.opacity=Math.min(255,p.opacity+20);INSTANCE.saveConfig();return true;}
                by+=29;String[] lines=lineLabels(name);int lw=(w-12)/3;
                for(int i=0;i<Math.min(6,lines.length);i++){int lx=x+(i%3)*(lw+6),ly=by+(i/3)*24;if(inside(mx,my,lx,ly,lw,20)){p.toggleLine(i);INSTANCE.saveConfig();return true;}}
                int py=by+(lines.length>3?50:26),px=x+w-236;
                if(inside(mx,my,px,py,38,20)){p.x=Math.max(0,p.x-5);INSTANCE.saveConfig();return true;}
                if(inside(mx,my,px+42,py,38,20)){p.x+=5;INSTANCE.saveConfig();return true;}
                if(inside(mx,my,px+84,py,38,20)){p.y=Math.max(0,p.y-5);INSTANCE.saveConfig();return true;}
                if(inside(mx,my,px+126,py,38,20)){p.y+=5;INSTANCE.saveConfig();return true;}
                if(inside(mx,my,px+168,py,68,20)){Minecraft.getInstance().setScreen(INSTANCE.new HudEditorScreen());return true;}
            }int dw=u(110),dh=Math.max(18,u(22));if(inside(mx,my,(l+r)/2-dw/2,b-u(34),dw,dh)){onClose();return true;}return super.mouseClicked(e,dbl);}
        private static double remaining(UpgradeSnapshot u){if(u.level()>=u.max())return 0;if(u.nextCost()<=0)return -1;if(u.name().equals("Tree Type")){double total=0;for(int i=u.level();i<u.max();i++){double c=treeCost(i);if(c<0)return u.nextCost();total+=c;}return total;}double scale=upgradeScale(u.name());if(scale<=0)return u.nextCost();int n=u.max()-u.level();if(Math.abs(scale-1)<0.00001)return u.nextCost()*n;return u.nextCost()*(Math.pow(scale,n)-1)/(scale-1);}
        private static double upgradeScale(String n){return switch(n){case "Sweep"->1.15;case "Fortune"->1.90;case "Tree Growth Speed"->1.40;case "Jump Height"->1.50;case "Walk Speed"->1.25;case "Throwing Axe Cooldown","Cut-off Rate"->3.0;case "Swing Range"->3.5;case "Turret","Timber","Throwing Axe Speed"->2.0;case "Meowltishot"->10.0;case "Extended Jumps"->5.0;case "Combo Tracker"->1.8;default->-1;};}
        private void title(GuiGraphics g,String s,int x,int y){g.drawString(font,s,x,y,0xFFF2F4F6,true);g.fill(x,y+15,x+Math.min(150,font.width(s)+18),y+17,0xFF344553);}
        private void row(GuiGraphics g,String a,String b,int x,int y,int w){box(g,x,y,w,25);g.drawString(font,a,x+9,y+8,0xFF9FAAB3,false);g.drawString(font,fit(b,w/2),x+w-font.width(fit(b,w/2))-9,y+8,0xFFF2F4F6,true);}
        private void quick(GuiGraphics g,int x,int y,int w,String s,net.minecraft.world.item.Item item,int mx,int my){boolean h=inside(mx,my,x,y,w,46);g.fill(x,y,x+w,y+46,h?0xFF303A44:0xFF20262C);bevel(g,x,y,x+w,y+46);g.renderItem(new ItemStack(item),x+9,y+15);g.drawString(font,fit(s,w-38),x+33,y+19,0xFFF2F4F6,true);}
        private void box(GuiGraphics g,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xFF252C33);bevel(g,x,y,x+w,y+h);}
        private void button(GuiGraphics g,int x,int y,int w,int h,String s,boolean hover){g.fill(x,y,x+w,y+h,hover?0xFF46535E:0xFF343D45);bevel(g,x,y,x+w,y+h);g.drawCenteredString(font,s,x+w/2,y+7,0xFFFFFFFF);}
        private void frame(GuiGraphics g,int l,int t,int r,int b){g.fill(l,t,r,b,0xE8313A42);g.fill(l+2,t+2,r-2,b-2,0xE820262C);g.fill(l+5,t+5,r-5,b-5,0xD911161A);}
        private void bevel(GuiGraphics g,int l,int t,int r,int b){g.fill(l,t,r,t+2,0xFF69747D);g.fill(l,t,l+2,b,0xFF69747D);g.fill(l,b-2,r,b,0xFF9FAAB3);g.fill(r-2,t,r,b,0xFF9FAAB3);}
        private boolean inside(int mx,int my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
        private String fit(String s,int max){if(s==null)return"";if(max<=8||font.width(s)<=max)return s;String e="...";return font.plainSubstrByWidth(s,Math.max(0,max-font.width(e)))+e;}
        private static double treeCost(int level){return switch(level){case 115->13e9;case 116->16e9;case 117->19e9;case 118->22e9;case 119->25e9;default->level>=120?0:-1;};}
        @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
        private enum Tab{HOME("Home",Items.GRASS_BLOCK),HUD("HUD",Items.COMPASS),UPGRADES("Upgrade Info",Items.ENCHANTED_BOOK),TRINKETS("Trinkets",Items.NETHER_STAR),WEATHER("Weather",Items.LIGHTNING_ROD),STOCKS("Stocks",Items.GOLD_INGOT),SESSION("Session",Items.CLOCK);final String label;final net.minecraft.world.item.Item icon;Tab(String label,net.minecraft.world.item.Item icon){this.label=label;this.icon=icon;}}
    }

    private final class HudEditorScreen extends Screen {
        private Panel dragging;
        private int offX,offY,startX,startY;
        private boolean moved;
        HudEditorScreen(){super(Component.literal("Treecutters HUD Editor"));}
        private List<Map.Entry<String,Panel>> panels(){return List.of(Map.entry("LPS",config.lps),Map.entry("Weather",config.weather),Map.entry("Stocks",config.stocks),Map.entry("Goal",config.goalPanel),Map.entry("Stock Events",config.events),Map.entry("Session",config.session));}
        @Override public void render(GuiGraphics g,int mx,int my,float d){
            super.render(g,mx,my,d);
            g.drawCenteredString(font,"HUD Editor",width/2,12,0xFFFFFFFF);
            for(var e:panels()){
                Panel p=e.getValue();
                int w=Math.max(96,font.width(e.getKey())+18);
                g.fill(p.x,p.y,p.x+w,p.y+42,0xCC181818);
                g.fill(p.x,p.y,p.x+w,p.y+1,0xFF55FF55);
                g.drawString(font,e.getKey(),p.x+5,p.y+5,0xFFAAFFAA,true);
                g.drawString(font,p.enabled?"Shown":"Hidden",p.x+5,p.y+20,p.enabled?0xFF55FF55:0xFFFF5555,true);
            }
        }
        @Override public boolean mouseClicked(MouseButtonEvent c,boolean dbl){
            for(var e:panels()){
                Panel p=e.getValue();
                int w=Math.max(96,font.width(e.getKey())+18);
                if(c.x()>=p.x&&c.x()<=p.x+w&&c.y()>=p.y&&c.y()<=p.y+42){
                    if(c.button()==1){p.enabled=!p.enabled;saveConfig();return true;}
                    if(c.button()==0){dragging=p;offX=(int)c.x()-p.x;offY=(int)c.y()-p.y;startX=p.x;startY=p.y;moved=false;return true;}
                }
            }
            return super.mouseClicked(c,dbl);
        }
        @Override public boolean mouseDragged(MouseButtonEvent c,double dx,double dy){
            if(dragging!=null){
                int nx=Math.max(0,Math.min(width-72,(int)c.x()-offX));
                int ny=Math.max(25,Math.min(height-45,(int)c.y()-offY));
                if(Math.abs(nx-startX)>3||Math.abs(ny-startY)>3)moved=true;
                dragging.x=nx;dragging.y=ny;return true;
            }
            return super.mouseDragged(c,dx,dy);
        }
        @Override public boolean mouseReleased(MouseButtonEvent c){
            if(dragging!=null){
                Panel p=dragging;
                dragging=null;
                saveConfig();
                if(!moved&&c.button()==0)Minecraft.getInstance().setScreen(new WidgetScreen(this,p,panelName(p)));
                return true;
            }
            return super.mouseReleased(c);
        }
        private String panelName(Panel p){for(var e:panels())if(e.getValue()==p)return e.getKey();return "Widget";}
        @Override public void removed(){saveConfig();super.removed();}
    }

    private final class WidgetScreen extends Screen {
        private final Screen parent;
        private final Panel panel;
        private final String name;
        WidgetScreen(Screen parent,Panel panel,String name){super(Component.literal(name));this.parent=parent;this.panel=panel;this.name=name;}
        @Override protected void init(){
            clearWidgets();
            int boxW=Math.min(420,width-24),x=(width-boxW)/2,y=Math.max(42,(height-244)/2),gap=6,bw=(boxW-gap)/2;
            addRenderableWidget(Button.builder(Component.literal(panel.enabled?"Widget: Shown":"Widget: Hidden"),b->{panel.enabled=!panel.enabled;rebuildWidgets();saveConfig();}).bounds(x,y,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal(panel.showHeader?"Header: Shown":"Header: Hidden"),b->{panel.showHeader=!panel.showHeader;rebuildWidgets();saveConfig();}).bounds(x+bw+gap,y,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal(panel.shadow?"Shadow: On":"Shadow: Off"),b->{panel.shadow=!panel.shadow;rebuildWidgets();saveConfig();}).bounds(x,y+24,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset widget"),b->{resetPanel(panel,name);rebuildWidgets();saveConfig();}).bounds(x+bw+gap,y+24,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal("Opacity -"),b->{panel.opacity=Math.max(0,panel.opacity-20);rebuildWidgets();saveConfig();}).bounds(x,y+48,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal("Opacity +"),b->{panel.opacity=Math.min(255,panel.opacity+20);rebuildWidgets();saveConfig();}).bounds(x+bw+gap,y+48,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal("Scale -"),b->{panel.scale=Math.max(0.65f,panel.scale-0.1f);rebuildWidgets();saveConfig();}).bounds(x,y+72,bw,20).build());
            addRenderableWidget(Button.builder(Component.literal("Scale +"),b->{panel.scale=Math.min(2.0f,panel.scale+0.1f);rebuildWidgets();saveConfig();}).bounds(x+bw+gap,y+72,bw,20).build());
            String[] labels=lineLabels(name);
            for(int i=0;i<labels.length&&i<6;i++){
                final int line=i;
                int yy=y+100+(i/2)*24;
                addRenderableWidget(Button.builder(Component.literal(labels[i]+": "+(panel.line(i)?"On":"Off")),b->{panel.toggleLine(line);rebuildWidgets();saveConfig();}).bounds(x+(i%2)*(bw+gap),yy,bw,20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,y+178,boxW,20).build());
        }
        @Override public void render(GuiGraphics g,int mx,int my,float d){
            g.fill(0,0,width,height,0xD8080B0D);
            super.render(g,mx,my,d);
            int y=Math.max(18,(height-244)/2-30);
            g.drawCenteredString(font,name+" Widget",width/2,y,0xFFFFFFFF);
            g.drawCenteredString(font,Math.round(panel.scale*100)+"% scale   "+Math.round(panel.opacity/255f*100)+"% background",width/2,y+15,0xFF9FB3A6);
        }
        @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    }

    private static String[] lineLabels(String name){return switch(name){case "LPS"->new String[]{"1 second","10 seconds","60 seconds","Peak"};case "Weather"->new String[]{"Weather / players","Drain","Tier timer","+100M time"};case "Stocks"->new String[]{"Price","Shares","Sell-all","Floor planner"};case "Goal"->new String[]{"Progress","Remaining","ETA"};case "Stock Events"->new String[]{"Event 1","Event 2","Event 3","Event 4","Event 5"};case "Session"->new String[]{"Time","Peak","Current"};default->new String[]{"Line 1","Line 2","Line 3","Line 4"};};}
    private static void resetPanel(Panel p,String name){int x=8,y=8;switch(name){case "Weather"->y=70;case "Stocks"->y=150;case "Goal"->{x=135;y=8;}case "Stock Events"->{x=135;y=80;}case "Session"->{x=270;y=8;}}p.x=x;p.y=y;p.opacity=170;p.enabled=!name.equals("Stock Events")&&!name.equals("Session");p.showHeader=true;p.shadow=true;p.scale=1f;p.lines=63;}
    private record Sample(long time,double gain){} private record UpgradeChoice(Slot slot,double score,String name){} private record UpgradeSnapshot(String name,int level,int max,double nextCost){}
    private static final class Panel{boolean enabled=true;int x,y;float scale=1f;int opacity=170;boolean showHeader=true,shadow=true;int lines=63;Panel(int x,int y){this.x=x;this.y=y;}Panel(int x,int y,boolean enabled){this.x=x;this.y=y;this.enabled=enabled;}boolean line(int i){return i<0||i>5||((lines>>i)&1)==1;}void toggleLine(int i){if(i>=0&&i<6)lines^=1<<i;}}
    private static final class Config{
        boolean upgradeOptimizer=true,weatherWarnings=true,welcomed=false;double buyCeiling=0,sellFloor=0,goal=0;
        Panel lps=new Panel(8,8),weather=new Panel(8,70),stocks=new Panel(8,150),goalPanel=new Panel(135,8),events=new Panel(135,80,false),session=new Panel(270,8,false);
        void fix(){if(lps==null)lps=new Panel(8,8);if(weather==null)weather=new Panel(8,70);if(stocks==null)stocks=new Panel(8,150);if(goalPanel==null)goalPanel=new Panel(135,8);if(events==null)events=new Panel(135,80,false);if(session==null)session=new Panel(270,8,false);}
    }
    @Override public String id() { return "treecutters"; }
    @Override public String name() { return "Treecutters Utilities"; }
    @Override public boolean isActive() { return treecuttersDetected && System.currentTimeMillis() - lastTreecuttersActionbar < 15000; }
    @Override public Screen configScreen(Screen parent) { return new ConfigScreen(parent); }

}
