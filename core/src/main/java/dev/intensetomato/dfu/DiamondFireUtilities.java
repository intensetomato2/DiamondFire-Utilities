package dev.intensetomato.dfu;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.intensetomato.dfu.api.DfuModule;
import dev.intensetomato.dfu.mixin.PlayerTabOverlayAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class DiamondFireUtilities implements ClientModInitializer {
    private static DiamondFireUtilities INSTANCE;
    private static final int HOTBAR_SLOT = 3;
    private final ModuleManager modules = new ModuleManager();
    private final DfuRegistryClient registry = new DfuRegistryClient();
    private boolean openNextTick;
    private String themeName = "DFU";
    private static final Gson GSON = new Gson();
    private final Path updateSettingsFile = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/module-updates.json");
    private UpdateSettings updateSettings = new UpdateSettings();

    @Override public void onInitializeClient() {
        INSTANCE = this;
        loadTheme();
        loadUpdateSettings();
        registry.loadCache();
        modules.loadInstalled();
        registry.refresh().thenRun(this::checkModuleUpdates);
        ClientTickEvents.END_CLIENT_TICK.register(client -> { if (openNextTick) { openNextTick = false; client.setScreen(new DfuScreen(client.screen)); } });
        HudRenderCallback.EVENT.register((g, dt) -> renderLobbyItem(g));
        ClientCommandRegistrationCallback.EVENT.register((d, r) -> d.register(literal("dfu")
            .executes(c -> { openNextTick = true; return 1; })
            .then(literal("import").then(argument("module", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(registry.moduleIds(), b))
                .executes(c -> {
                    String id = StringArgumentType.getString(c, "module");
                    local("§7Checking official DFU registry for §f" + id + "§7...");
                    registry.install(id).thenAccept(result -> Minecraft.getInstance().execute(() -> {
                        if (!result.success()) {
                            local("§c" + result.message());
                            return;
                        }
                        ModuleManager.LoadResult loaded = modules.load(result.path());
                        if (loaded.success()) rememberInstalledHash(id, result.path());
                        local((loaded.success() ? "§a" : "§c") + (loaded.success() ? result.message() + " " + loaded.message() : loaded.message()));
                    }));
                    return 1;
                })))
            .then(literal("delete").then(argument("module", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(modules.moduleIds(), b))
                .executes(c -> {
                    String id = StringArgumentType.getString(c, "module");
                    ModuleManager.DeleteResult result = modules.delete(id);
                    local((result.success() ? "§a" : "§c") + result.message());
                    return 1;
                })))
        ));
    }

    private void loadUpdateSettings() {
        try {
            if (Files.isRegularFile(updateSettingsFile)) {
                UpdateSettings loaded = GSON.fromJson(Files.readString(updateSettingsFile), UpdateSettings.class);
                if (loaded != null) updateSettings = loaded;
            }
        } catch (Exception ignored) {}
        if (updateSettings.modules == null) updateSettings.modules = new LinkedHashMap<>();
        for (String id : modules.moduleIds()) ensureUpdateState(id);
    }

    private synchronized ModuleUpdateState ensureUpdateState(String id) {
        ModuleUpdateState state = updateSettings.modules.computeIfAbsent(id, k -> new ModuleUpdateState());
        if (state.hash == null || state.hash.isBlank()) {
            Path file = modules.packageFile(id);
            if (Files.isRegularFile(file)) state.hash = registry.packageHash(file);
            saveUpdateSettings();
        }
        return state;
    }

    private synchronized void saveUpdateSettings() {
        try {
            Files.createDirectories(updateSettingsFile.getParent());
            Files.writeString(updateSettingsFile, GSON.toJson(updateSettings));
        } catch (Exception ignored) {}
    }

    private boolean autoUpdateEnabled(String id) { return ensureUpdateState(id).enabled; }

    private void setAutoUpdate(String id, boolean enabled) {
        ensureUpdateState(id).enabled = enabled;
        saveUpdateSettings();
        if (enabled) checkModuleUpdate(id, false);
    }

    private void rememberInstalledHash(String id, Path path) {
        ModuleUpdateState state = ensureUpdateState(id);
        state.hash = registry.packageHash(path);
        saveUpdateSettings();
    }

    private void checkModuleUpdates() {
        for (String id : modules.moduleIds()) if (autoUpdateEnabled(id)) checkModuleUpdate(id, true);
    }

    private void checkModuleUpdate(String id, boolean quiet) {
        if (!autoUpdateEnabled(id) || !registry.verified(id)) return;
        ModuleUpdateState state = ensureUpdateState(id);
        registry.manifest(id).thenAccept(manifest -> {
            if (manifest == null || manifest.sha256 == null || manifest.sha256.isBlank()) return;
            if (state.hash == null || state.hash.isBlank()) {
                state.hash = registry.packageHash(modules.packageFile(id));
                saveUpdateSettings();
                return;
            }
            if (manifest.sha256.equalsIgnoreCase(state.hash)) return;
            if (!quiet) local("§7Update found for §f" + id + "§7. Installing...");
            registry.install(id).thenAccept(result -> Minecraft.getInstance().execute(() -> {
                if (!result.success()) { local("§cAuto-update failed for " + id + ": " + result.message()); return; }
                ModuleManager.LoadResult loaded = modules.load(result.path());
                if (!loaded.success()) { local("§cAuto-update downloaded but could not load " + id + ": " + loaded.message()); return; }
                rememberInstalledHash(id, result.path());
                local("§aUpdated " + id + " to verified hash " + manifest.sha256.substring(0, Math.min(8, manifest.sha256.length())) + ".");
            }));
        }).exceptionally(error -> { if (!quiet) local("§cUpdate check failed for " + id + "."); return null; });
    }

    private static final class UpdateSettings { Map<String, ModuleUpdateState> modules = new LinkedHashMap<>(); }
    private static final class ModuleUpdateState { boolean enabled; String hash = ""; }

    private void loadTheme() {
        try {
            Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/theme.txt");
            if (Files.exists(p)) themeName = Files.readString(p).trim();
        } catch (Exception ignored) {}
    }

    private void saveTheme(String name) {
        themeName = name;
        try {
            Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("diamondfire-utilities/theme.txt");
            Files.createDirectories(p.getParent());
            Files.writeString(p, name);
        } catch (Exception ignored) {}
    }

    public static boolean handleLobbyUse() {
        DiamondFireUtilities self = INSTANCE;
        Minecraft mc = Minecraft.getInstance();
        if (self == null || mc.player == null || !self.inDiamondFireLobby() || mc.player.getInventory().getSelectedSlot() != HOTBAR_SLOT) return false;
        mc.setScreen(new DfuScreen(mc.screen));
        return true;
    }

    private boolean inDiamondFireLobby() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getCurrentServer() == null) return false;
        String host = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (!host.contains("diamondfire")) return false;
        for (DfuModule module : modules.modules()) if (module.isActive()) return false;
        try {
            Component h = ((PlayerTabOverlayAccessor)(Object)mc.gui.getTabList()).dfu$getHeader();
            if (h == null) return true;
            return h.getString().toLowerCase(Locale.ROOT).contains("diamondfire");
        } catch (Throwable ignored) { return true; }
    }

    private void renderLobbyItem(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!inDiamondFireLobby() || mc.player == null || mc.screen != null) return;
        int x = g.guiWidth() / 2 - 90 + HOTBAR_SLOT * 20 + 2;
        int y = g.guiHeight() - 19;
        g.renderItem(new ItemStack(Items.DRAGON_EGG), x, y);
        if (mc.player.getInventory().getSelectedSlot() == HOTBAR_SLOT) {
            String name = "DiamondFire Utilities";
            int tx = (g.guiWidth() - mc.font.width(name)) / 2;
            g.fill(tx - 4, g.guiHeight() - 43, tx + mc.font.width(name) + 4, g.guiHeight() - 29, 0x90000000);
            g.drawString(mc.font, name, tx, g.guiHeight() - 40, 0xFFFF55FF, true);
        }
    }

    public static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("§8[§dDFU§8] " + text), false);
    }

    public static Screen configScreen(Screen parent) { return new DfuScreen(parent); }

    private static final class DfuScreen extends Screen {
        private static final Identifier BACKGROUND = Identifier.fromNamespaceAndPath("diamondfire_utilities", "textures/gui/dfu_background.png");
        private final Screen parent;
        private Page page = Page.HOME;
        private String status = "";
        private int statusColor = 0xFFFFFFFF;
        DfuScreen(Screen parent) { super(Component.literal("DiamondFire Utilities")); this.parent = parent; }
        private double uiScale() { return Math.min(1.0, Math.min((width - 20) / 760.0, (height - 20) / 430.0)); }
        private int boxW() { return 760; }
        private int boxH() { return 430; }
        private int left() { return 0; }
        private int top() { return 0; }
        private int originX() { return (int)Math.round((width - boxW() * uiScale()) / 2.0); }
        private int originY() { return (int)Math.round((height - boxH() * uiScale()) / 2.0); }
        private int localX(double x) { return (int)Math.floor((x - originX()) / uiScale()); }
        private int localY(double y) { return (int)Math.floor((y - originY()) / uiScale()); }
        @Override protected void init() { clearWidgets(); }
        private void installModule(String id) { status="Installing " + id + "..."; statusColor=0xFFFFFF55; INSTANCE.registry.install(id).thenAccept(result -> Minecraft.getInstance().execute(() -> { if(!result.success()){status=result.message();statusColor=0xFFFF7777;return;} ModuleManager.LoadResult loaded=INSTANCE.modules.load(result.path()); if(loaded.success()) INSTANCE.rememberInstalledHash(id,result.path()); status=loaded.success()?result.message():loaded.message();statusColor=loaded.success()?0xFF77FF88:0xFFFF7777;})); }
        private void removeModule(String id) { ModuleManager.DeleteResult r=INSTANCE.modules.delete(id);status=r.message();statusColor=r.success()?0xFF77FF88:0xFFFF7777; }
        private void refreshRegistry() { status="Refreshing...";statusColor=0xFFFFFF55;INSTANCE.registry.refresh().whenComplete((r,e)->Minecraft.getInstance().execute(()->{status=e==null?"Registry refreshed":"Refresh failed";statusColor=e==null?0xFF77FF88:0xFFFF7777;})); }
        @Override public void render(GuiGraphics g,int mx,int my,float d) {
            g.fill(0,0,width,height,0x50000000);
            double scale=uiScale();int ox=originX(),oy=originY();int lmx=localX(mx),lmy=localY(my);
            g.pose().pushMatrix();g.pose().translate(ox,oy);g.pose().scale((float)scale,(float)scale);
            int l=0,t=0,r=boxW(),b=boxH();
            frame(g,l,t,r,b);
            header(g,l+u(6),t+u(6),r-u(6),t+u(48));
            int navX=l+u(10), navY=t+u(58), navW=u(150), contentX=navX+navW+u(8), railW=u(172), gap=u(8);
            int bodyBottom=b-u(48), contentW=r-u(10)-contentX-railW-gap;
            panel(g,navX,navY,navW,bodyBottom-navY);
            drawNav(g,navX+u(6),navY+u(7),navW-u(12),lmx,lmy);
            panel(g,contentX,navY,contentW,bodyBottom-navY);
            panel(g,contentX+contentW+gap,navY,railW,bodyBottom-navY);
            int cx=contentX+u(12),cy=navY+u(12),cw=contentW-u(24);
            if(page==Page.HOME) home(g,cx,cy,cw,lmx,lmy);
            if(page==Page.MODULES) modules(g,cx,cy,cw,lmx,lmy);
            if(page==Page.STYLE) appearance(g,cx,cy,cw);
            if(page==Page.ABOUT) about(g,cx,cy,cw);
            rail(g,contentX+contentW+gap+u(10),navY+u(12),railW-u(20));
            if(!status.isBlank()) g.drawCenteredString(font,fit(status,Math.max(80,boxW()-u(260))),(l+r)/2,b-u(42),statusColor);
            int closeW=u(140),closeH=Math.max(18,u(22));
            button(g,(l+r)/2-closeW/2,b-u(31),closeW,closeH,"Close",inside(lmx,lmy,(l+r)/2-closeW/2,b-u(31),closeW,closeH));
            g.pose().popMatrix();
            super.render(g,mx,my,d);
        }
        private int u(int n) { return n; }
        private void header(GuiGraphics g,int l,int t,int r,int b) {
            g.fill(l,t,r,b,0xFF11171C);bevel(g,l,t,r,b);
            int c=(l+r)/2;
            g.renderItem(new ItemStack(Items.DIAMOND),c-132,t+12);
            g.renderItem(new ItemStack(Items.DIAMOND),c+116,t+12);
            g.drawCenteredString(font,"DiamondFire Utilities",c,t+16,0xFFFFFFFF);
        }
        private void drawNav(GuiGraphics g,int x,int y,int w,int mx,int my) {
            int yy=y,step=u(42);
            nav(g,x,yy,w,"Home",Items.GRASS_BLOCK,page==Page.HOME,mx,my); yy+=step;
            for(DfuModule m:INSTANCE.modules.modules()) { if(yy+36>top()+boxH()-116) break; nav(g,x,yy,w,shortModuleName(m.name()),Items.IRON_AXE,false,mx,my); yy+=step; }
            nav(g,x,yy,w,"Modules",Items.CHEST,page==Page.MODULES,mx,my); yy+=step;
            nav(g,x,yy,w,"Style",Items.PAINTING,page==Page.STYLE,mx,my); yy+=step;
            nav(g,x,yy,w,"About",Items.BOOK,page==Page.ABOUT,mx,my);
        }
        private void nav(GuiGraphics g,int x,int y,int w,String text,net.minecraft.world.item.Item item,boolean active,int mx,int my) {
            int rh=Math.max(24,u(34)); boolean hover=inside(mx,my,x,y,w,rh);
            g.fill(x,y,x+w,y+rh,active?0xFF344B5A:hover?0xFF303941:0xFF1B2228);
            bevel(g,x,y,x+w,y+rh);
            if(active){g.fill(x,y,x+2,y+rh,0xFF74E9FF);g.fill(x,y,x+w,y+2,0xFF74E9FF);g.fill(x+w-2,y,x+w,y+rh,0xFF74E9FF);g.fill(x,y+rh-2,x+w,y+rh,0xFF74E9FF);}
            g.renderItem(new ItemStack(item),x+u(9),y+Math.max(4,(rh-16)/2));
            g.drawString(font,fit(text,Math.max(20,w-u(40))),x+u(34),y+(rh-font.lineHeight)/2,active?0xFFBDF6FF:0xFFF4F7F9,true);
        }
        private void home(GuiGraphics g,int x,int y,int w,int mx,int my) {
            hero(g,x,y,w,Math.max(1,top()+boxH()-u(48)-y-u(12)));
        }
        private int bodyHeight(){return boxH()-u(126);}
        private void hero(GuiGraphics g,int x,int y,int w,int h) {
            imageCover(g,BACKGROUND,x,y,w,h,2048,1076);
            g.fill(x,y,x+w,y+h,0x76080D11);
            bevel(g,x,y,x+w,y+h);
            int cy=y+h/2;
            g.renderItem(new ItemStack(Items.DIAMOND),x+w/2-8,cy-62);
            g.drawCenteredString(font,"Welcome to",x+w/2,cy-27,0xFFF5F7F8);
            g.drawCenteredString(font,"DiamondFire Utilities",x+w/2,cy-5,0xFF72E7F8);
            g.fill(x+w/2-92,cy+24,x+w/2+92,cy+26,0xAA72E7F8);
            g.drawCenteredString(font,"Choose a page or module from the sidebar.",x+w/2,cy+47,0xFFE2E8EC);
        }
        private void rail(GuiGraphics g,int x,int y,int w) {
            section(g,"Player Info",x,y,w);
            box(g,x,y+24,w,112);
            Minecraft mc=Minecraft.getInstance();
            String name=mc.player==null?"--":mc.player.getGameProfile().name();
            if(mc.player!=null){
                Identifier skin=mc.player.getSkin().body().texturePath();
                g.blit(RenderPipelines.GUI_TEXTURED,skin,x+10,y+37,8,8,28,28,8,8,64,64);
                g.blit(RenderPipelines.GUI_TEXTURED,skin,x+10,y+37,40,8,28,28,8,8,64,64);
            } else g.renderItem(new ItemStack(Items.PLAYER_HEAD),x+16,y+43);
            g.drawString(font,fit(name,w-54),x+46,y+46,0xFFF4F7F9,true);
            g.drawString(font,"Server",x+10,y+78,0xFF9FAAB3,false);
            g.drawString(font,"DiamondFire",x+w-font.width("DiamondFire")-10,y+78,0xFF72E7F8,true);
            String ping="--";
            if(mc.player!=null&&mc.getConnection()!=null){var info=mc.getConnection().getPlayerInfo(mc.player.getUUID());if(info!=null)ping=info.getLatency()+" ms";}
            g.drawString(font,"Ping",x+10,y+101,0xFF9FAAB3,false);
            g.drawString(font,ping,x+w-font.width(ping)-10,y+101,0xFF73F19A,true);
            section(g,"DFU Status",x,y+158,w);
            box(g,x,y+182,w,84);
            g.renderItem(new ItemStack(Items.EMERALD),x+10,y+198);
            g.drawString(font,"Core",x+36,y+202,0xFFDCE3E8,false);
            g.drawString(font,"Ready",x+w-font.width("Ready")-10,y+202,0xFF62EF8C,true);
            long active=INSTANCE.modules.modules().stream().filter(DfuModule::isActive).count();
            String a=active+" / "+INSTANCE.modules.modules().size();
            g.drawString(font,"Active modules",x+10,y+232,0xFF9FAAB3,false);
            g.drawString(font,a,x+w-font.width(a)-10,y+232,0xFFF4F7F9,true);
        }
        private void imageCover(GuiGraphics g,Identifier texture,int x,int y,int w,int h,int tw,int th){
            double target=(double)w/h,image=(double)tw/th;int su=0,sv=0,sw=tw,sh=th;
            if(image>target){sw=(int)Math.round(th*target);su=(tw-sw)/2;}else{sh=(int)Math.round(tw/target);sv=(th-sh)/2;}
            g.blit(RenderPipelines.GUI_TEXTURED,texture,x,y,(float)su,(float)sv,w,h,sw,sh,tw,th);
        }
        private void modules(GuiGraphics g,int x,int y,int w,int mx,int my) {
            section(g,"Modules",x,y,w);button(g,x+w-76,y-5,76,22,"Refresh",inside(mx,my,x+w-76,y-5,76,22));int yy=y+28;
            for(DfuModule m:INSTANCE.modules.modules()){if(yy+42>top()+boxH()-110)break;box(g,x,yy,w,38);g.renderItem(new ItemStack(Items.IRON_AXE),x+8,yy+11);g.drawString(font,fit(m.name(),w-130),x+33,yy+9,0xFFF2F4F6,true);g.drawString(font,m.isActive()?"Active":"Installed",x+33,yy+23,m.isActive()?0xFF55FF88:0xFF9FAAB3,false);String au=INSTANCE.autoUpdateEnabled(m.id())?"Auto: ON":"Auto: OFF";int auColor=INSTANCE.autoUpdateEnabled(m.id())?0xFF55FF88:0xFF9FAAB3;g.drawString(font,au,x+w-112,yy+8,auColor,false);g.drawString(font,"Remove",x+w-52,yy+23,0xFFFF7777,false);yy+=43;}
            section(g,"Available",x,yy+2,w);yy+=25;
            for(DfuRegistryClient.RegistryEntry e:INSTANCE.registry.entries()){if(INSTANCE.modules.get(e.id).isPresent()||!e.verified||yy+35>top()+boxH()-58)continue;box(g,x,yy,w,32);g.renderItem(new ItemStack(Items.EMERALD),x+8,yy+8);g.drawString(font,fit(e.name==null?e.id:e.name,w-125),x+33,yy+7,0xFFF2F4F6,true);g.drawString(font,"Install",x+w-48,yy+12,0xFF55FF88,true);yy+=37;}
        }
        private void appearance(GuiGraphics g,int x,int y,int w) { section(g,"Style",x,y,w);box(g,x,y+28,w,78);g.renderItem(new ItemStack(Items.PAINTING),x+14,y+52);g.drawString(font,"Minecraft",x+42,y+44,0xFFF2F4F6,true);g.drawString(font,"Dark panels, vanilla icons, cyan selection",x+42,y+62,0xFFB5C0C8,false);g.drawString(font,"Selected",x+w-font.width("Selected")-12,y+72,0xFF55FF88,true); }
        private void about(GuiGraphics g,int x,int y,int w) { section(g,"About",x,y,w);box(g,x,y+28,w,100);g.renderItem(new ItemStack(Items.BOOK),x+14,y+45);g.drawString(font,"DiamondFire Utilities",x+42,y+44,0xFFF2F4F6,true);g.drawString(font,"Community-made utilities for DiamondFire.",x+14,y+72,0xFFB5C0C8,false);g.drawString(font,"Modules keep game-specific features separate from Core.",x+14,y+90,0xFFB5C0C8,false); }
        private void moduleTile(GuiGraphics g,DfuModule m,int x,int y,int w,int mx,int my) { boolean h=inside(mx,my,x,y,w,48);g.fill(x,y,x+w,y+48,h?0xFF2B3740:0xFF192126);bevel(g,x,y,x+w,y+48);g.renderItem(new ItemStack(Items.IRON_AXE),x+10,y+16);g.drawString(font,fit(m.name(),w-105),x+38,y+12,0xFFF5F7F8,true);g.drawString(font,m.isActive()?"Active":"Installed",x+38,y+29,m.isActive()?0xFF62EF8C:0xFF9FAAB3,false);g.drawString(font,">",x+w-18,y+20,0xFFD5DDE2,true); }
        @Override public boolean mouseClicked(MouseButtonEvent e,boolean dbl) {
            if(e.button()!=0)return super.mouseClicked(e,dbl);int l=0,t=0,r=boxW(),b=boxH(),mx=localX(e.x()),my=localY(e.y());int navX=l+u(16),navY=t+u(65),navW=u(138),yy=navY,rh=Math.max(24,u(34)),step=u(42);
            if(inside(mx,my,navX,yy,navW,rh)){page=Page.HOME;return true;}yy+=step;
            for(DfuModule m:INSTANCE.modules.modules()){if(yy+rh>t+boxH()-u(116))break;if(inside(mx,my,navX,yy,navW,rh)){Minecraft.getInstance().setScreen(m.configScreen(this));return true;}yy+=step;}
            if(inside(mx,my,navX,yy,navW,rh)){page=Page.MODULES;return true;}yy+=step;if(inside(mx,my,navX,yy,navW,rh)){page=Page.STYLE;return true;}yy+=step;if(inside(mx,my,navX,yy,navW,rh)){page=Page.ABOUT;return true;}
            int contentX=l+168,railW=172,gap=8,contentW=r-10-contentX-railW-gap,cx=contentX+12,cy=t+70,cw=contentW-24;
            if(page==Page.MODULES){if(inside(mx,my,cx+cw-76,cy-5,76,22)){refreshRegistry();return true;}int ry=cy+28;for(DfuModule m:INSTANCE.modules.modules()){if(ry+42>t+boxH()-110)break;if(inside(mx,my,cx,ry,cw,38)){if(mx>cx+cw-75&&my>=ry+18)removeModule(m.id());else if(mx>cx+cw-135){INSTANCE.setAutoUpdate(m.id(),!INSTANCE.autoUpdateEnabled(m.id()));status="Auto-update " +(INSTANCE.autoUpdateEnabled(m.id())?"enabled":"disabled")+" for "+shortModuleName(m.name());statusColor=0xFF77FF88;}else Minecraft.getInstance().setScreen(m.configScreen(this));return true;}ry+=43;}ry+=25;for(DfuRegistryClient.RegistryEntry re:INSTANCE.registry.entries()){if(INSTANCE.modules.get(re.id).isPresent()||!re.verified||ry+35>t+boxH()-58)continue;if(inside(mx,my,cx,ry,cw,32)){installModule(re.id);return true;}ry+=37;}}
            if(inside(mx,my,(l+r)/2-70,b-31,140,22)){onClose();return true;}return super.mouseClicked(e,dbl);
        }
        private String shortModuleName(String s){return s.endsWith(" Utilities")?s.substring(0,s.length()-10):s;}
        private void section(GuiGraphics g,String s,int x,int y,int w){g.drawString(font,s,x,y,0xFFF4F7F9,true);g.fill(x,y+16,x+Math.min(w,Math.max(70,font.width(s)+28)),y+18,0xFF59656E);}
        private void panel(GuiGraphics g,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xDD11181D);bevel(g,x,y,x+w,y+h);}
        private void box(GuiGraphics g,int x,int y,int w,int h){g.fill(x,y,x+w,y+h,0xFF202930);bevel(g,x,y,x+w,y+h);}
        private void button(GuiGraphics g,int x,int y,int w,int h,String s,boolean hover){g.fill(x,y,x+w,y+h,hover?0xFF42525E:0xFF303B43);bevel(g,x,y,x+w,y+h);g.drawCenteredString(font,s,x+w/2,y+7,0xFFFFFFFF);}
        private void frame(GuiGraphics g,int l,int t,int r,int b){g.fill(l,t,r,b,0xE866717A);g.fill(l+2,t+2,r-2,b-2,0xE82B343B);g.fill(l+5,t+5,r-5,b-5,0xD90E1418);}
        private void bevel(GuiGraphics g,int l,int t,int r,int b){g.fill(l,t,r,t+2,0xFF69757E);g.fill(l,t,l+2,b,0xFF69757E);g.fill(l,b-2,r,b,0xFF303940);g.fill(r-2,t,r,b,0xFF303940);}
        private boolean inside(int mx,int my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
        private String fit(String v,int max){if(v==null)return"";if(max<=8||font.width(v)<=max)return v;String e="...";return font.plainSubstrByWidth(v,Math.max(0,max-font.width(e)))+e;}
        @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
        private enum Page{HOME,MODULES,STYLE,ABOUT}
    }

}