package cc.hyperium.handlers.handlers.animation.cape;

import cc.hyperium.Hyperium;
import cc.hyperium.event.InvokeEvent;
import cc.hyperium.event.WorldChangeEvent;
import cc.hyperium.mods.sk1ercommon.Multithreading;
import cc.hyperium.netty.utils.Utils;
import cc.hyperium.purchases.HyperiumPurchase;
import cc.hyperium.purchases.PurchaseApi;
import cc.hyperium.utils.CapeUtils;
import cc.hyperium.utils.DownloadTask;
import cc.hyperium.utils.JsonHolder;
import cc.hyperium.utils.UUIDUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.FileUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.SharedDrawable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class BibHandler {

    public static final ReentrantLock LOCK = new ReentrantLock();
    private final ConcurrentHashMap<UUID, IBib> bibs = new ConcurrentHashMap<>();
    private final ResourceLocation loadingResource = new ResourceLocation("");
    private final File CACHE_DIR;
    private ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<>();
    private SharedDrawable drawable;


    public BibHandler() {
        try {
            drawable = new SharedDrawable(Display.getDrawable());

        } catch (LWJGLException e) {
            e.printStackTrace();
        }

        CACHE_DIR = new File(Hyperium.folder, "CACHE_DIR");
        CACHE_DIR.mkdir();
        Runtime.getRuntime().addShutdownHook(new Thread(CACHE_DIR::delete));
    }

    @InvokeEvent
    public void worldSwap(WorldChangeEvent event) {
        UUID id = UUIDUtil.getClientUUID();
        IBib selfCape = id == null ? null : bibs.get(id);
        try {
            LOCK.lock();

            for (IBib cape : bibs.values()) {
                if (selfCape != null && selfCape.equals(cape))
                    continue;
                cape.delete(Minecraft.getMinecraft().getTextureManager());
            }
            bibs.clear();
            if (selfCape != null)
                bibs.put(id, selfCape);
        } finally {
            LOCK.unlock();
        }
    }

    public void loadStaticCape(final UUID uuid, String url) {
        System.out.println("Fetching " + url);
        if (bibs.get(uuid) != null && !bibs.get(uuid).equals(NullBib.INSTANCE))
            return;
        bibs.put(uuid, NullBib.INSTANCE);

        ResourceLocation resourceLocation = new ResourceLocation(
                String.format("hyperium/bibs/%s.png", System.nanoTime())
        );

        TextureManager textureManager = Minecraft.getMinecraft().getTextureManager();
        ThreadDownloadImageData threadDownloadImageData = new ThreadDownloadImageData(null, url, null, new IImageBuffer() {

            @Override
            public BufferedImage parseUserSkin(BufferedImage image) {
                return CapeUtils.parseCape(image);
            }

            @Override
            public void skinAvailable() {
                BibHandler.this.setBib(uuid, new StaticBib(resourceLocation));
            }
        });
        try {
            LOCK.lock();
            textureManager.loadTexture(resourceLocation, threadDownloadImageData);
        } catch (Exception e) {

        } finally {
            LOCK.unlock();
        }
    }

    public void setBib(UUID uuid, IBib cape) {
        bibs.put(uuid, cape);
    }

    public synchronized void loadDynamicBib(final UUID uuid, String url) throws IOException, ExecutionException, InterruptedException {
        if (bibs.get(uuid) != null && !bibs.get(uuid).equals(NullBib.INSTANCE))
            return;
        bibs.put(uuid, NullBib.INSTANCE);

        File file = new File(CACHE_DIR, uuid.toString());
        JsonHolder holder = Utils.get("https://api.hyperium.cc/bibs/" + uuid.toString());

        file.mkdirs();
        File file1 = new File(file, "cache.txt");
        boolean fetch = true;
        if (file1.exists()) {
            String s = FileUtils.readFileToString(file1, "UTF-8");
            if (s.equalsIgnoreCase(url)) {
                fetch = false;
            }
        }
        if (fetch) {
            DownloadTask task = new DownloadTask(
                    url,
                    file.getAbsolutePath());
            task.execute();
            task.get();
            unzip(new File(file, task.getFileName()).getAbsolutePath(), file.getAbsolutePath());
            file1.createNewFile();
            FileUtils.write(file1, url);
        }
        int img = 0;
        try {
            drawable.makeCurrent();

            File tmp;
            ArrayList<ResourceLocation> locations = new ArrayList<>();
            TextureManager textureManager = Minecraft.getMinecraft().getTextureManager();
            final int[] i = {0};

            while ((tmp = new File(file, "img" + img + ".png")).exists()) {
                System.out.println("processing frame: " + i[0]);
                try {
                    BufferedImage read = ImageIO.read(tmp);
                    ResourceLocation resourceLocation = new ResourceLocation(
                            String.format("hyperium/dynamic_capes/%s_%s.png", file.getName(), i[0]));
                    locations.add(resourceLocation);
                    CapeTexture capeTexture = new CapeTexture(read);
                    textureManager.loadTexture(resourceLocation, capeTexture);
                    capeTexture.clearTextureData();
                    img++;
                    i[0]++;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }


            int finalImg = img;
            setBib(uuid, new DynamicBib(locations, holder.optInt("delay"), finalImg));
        } catch (LWJGLException e) {
            e.printStackTrace();
        } finally {
            try {
                drawable.releaseContext();
            } catch (LWJGLException e) {
                e.printStackTrace();
            }
        }

    }


    public ResourceLocation getBib(final AbstractClientPlayer player) {
        UUID uuid = player.getUniqueID();

        if (isRealPlayer(uuid)) {
            IBib cape = bibs.getOrDefault(uuid, null);
            if (cape == null) {
                setBib(player.getUniqueID(), NullBib.INSTANCE);
                Multithreading.runAsync(() -> {
                    HyperiumPurchase hyperiumPurchase = PurchaseApi.getInstance()
                            .getPackageSync(uuid);
                    JsonHolder holder = hyperiumPurchase.getPurchaseSettings().optJSONObject("cape");
                    String s = holder.optString("type");
                    if (s.equalsIgnoreCase("CUSTOM_GIF")) {
                        String url = holder.optString("url");
                        if (!url.isEmpty()) {
                            try {
                                loadDynamicBib(uuid, url);
                            } catch (IOException | ExecutionException | InterruptedException e) {
                                e.printStackTrace();
                            }
                            return;
                        }
                    } else if (s.equalsIgnoreCase("CUSTOM_IMAGE")) {
                        loadStaticCape(uuid, holder.optString("url"));
                        return;
                    } else if (!s.isEmpty()) {
                        JsonHolder jsonHolder = PurchaseApi.getInstance().getBibAtlas()
                                .optJSONObject(s);
                        String url = jsonHolder.optString("url");
                        if (!url.isEmpty()) {
                            loadStaticCape(uuid, url);
                            return;
                        }
                    }
                    loadStaticCape(uuid,
                            "http://s.optifine.net/bibs/" + player.getGameProfile().getName()
                                    + ".png");
                });
                return bibs.getOrDefault(uuid, NullBib.INSTANCE).get();
            }

            if (cape.equals(NullBib.INSTANCE)) {
                return null;
            }
            return cape.get();
        } else {
            return null;
        }
    }

    public boolean isRealPlayer(UUID uuid) {
        String s = uuid.toString().replace("-", "");
        if (s.length() == 32 && s.charAt(12) != '4') {
            return false;
        } else {
            return true;
        }
    }


    public void deleteBib(UUID id) {
        this.bibs.remove(id);
    }

    private void unzip(String zipFilePath, String destDir) {
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) dir.mkdirs();
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                System.out.println("Unzipping to " + newFile.getAbsolutePath());
                //create directories for sub directories in zip
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
