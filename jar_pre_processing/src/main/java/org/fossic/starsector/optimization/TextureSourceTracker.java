package org.fossic.starsector.optimization;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.ImageProducer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.TileObserver;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

/**
 * 把编码内容摘要关联到解码图片，并为持久化命中提供按需物化的兼容占位图。
 *
 * <p>冷 miss 仍返回解码器创建的原始 {@link BufferedImage}，只在弱身份表中记录摘要，
 * 不改变图片 class。热 hit 才返回占位图；游戏的图片处理器调用边界会先执行
 * {@link #invalidate(BufferedImage)}，从而强制物化原图并禁止复用缓存转换结果。
 */
public final class TextureSourceTracker {
    private static final ReferenceQueue<BufferedImage> COLLECTED_IMAGES =
            new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, String> SOURCE_HASHES =
            new HashMap<>();

    private TextureSourceTracker() {
    }

    static void track(BufferedImage image, String sourceHash) {
        if (image == null || sourceHash == null) {
            return;
        }
        synchronized (SOURCE_HASHES) {
            expungeCollectedImages();
            SOURCE_HASHES.put(
                    new IdentityWeakReference(image, COLLECTED_IMAGES),
                    sourceHash);
        }
    }

    static String takeSourceHash(BufferedImage image) {
        if (image == null || image instanceof CachedTextureImage) {
            return null;
        }
        synchronized (SOURCE_HASHES) {
            expungeCollectedImages();
            return SOURCE_HASHES.remove(
                    new IdentityWeakReference(image, null));
        }
    }

    static TextureConversionCache.CachedTexture cachedTexture(
            BufferedImage image) {
        return image instanceof CachedTextureImage cached
                ? cached.cachedTexture()
                : null;
    }

    static BufferedImage imageForConversion(BufferedImage image) {
        return image instanceof CachedTextureImage cached
                ? cached.materialize()
                : image;
    }

    static BufferedImage cachedImage(
            byte[] encodedSource,
            TextureConversionCache.CachedTexture cached,
            DecodedImageLoader decoder) {
        if (encodedSource == null || cached == null || decoder == null) {
            throw new IllegalArgumentException(
                    "cached image inputs must not be null");
        }
        return new CachedTextureImage(encodedSource, cached, decoder);
    }

    /** 在任何 mod 图片处理器执行前调用；处理后的图片不再按原编码摘要复用。 */
    public static void invalidate(BufferedImage image) {
        if (image instanceof CachedTextureImage cached) {
            cached.invalidateAndMaterialize();
        }
        synchronized (SOURCE_HASHES) {
            removeIdentity(image);
        }
    }

    /**
     * 在任意 mod 图片处理器边界把缓存占位对象替换为真实解码器返回的图片。
     * 处理器因此观察不到 1x1 {@link BufferedImage} 子类，冷/暖启动的 class、
     * raster、properties 与 identity 语义保持一致。
     */
    public static BufferedImage prepareForProcessor(BufferedImage image) {
        BufferedImage prepared = image;
        if (image instanceof CachedTextureImage cached) {
            prepared = cached.invalidateAndMaterialize();
        }
        synchronized (SOURCE_HASHES) {
            removeIdentity(image);
            if (prepared != image) {
                removeIdentity(prepared);
            }
        }
        return prepared;
    }

    /**
     * TextureLoader 自身只需要 alpha 标志。缓存仍有效时直接返回元数据；处理器使其失效后
     * 则先物化原图，保持实际 ColorModel 语义。
     */
    public static boolean hasAlpha(BufferedImage image) {
        if (image instanceof CachedTextureImage cached) {
            return cached.hasAlphaForEngine();
        }
        return image.getColorModel().hasAlpha();
    }

    static void resetForTests() {
        synchronized (SOURCE_HASHES) {
            SOURCE_HASHES.clear();
            while (COLLECTED_IMAGES.poll() != null) {
                // drain stale references left by previous tests
            }
        }
    }

    private static void removeIdentity(BufferedImage image) {
        expungeCollectedImages();
        SOURCE_HASHES.remove(new IdentityWeakReference(image, null));
    }

    private static void expungeCollectedImages() {
        IdentityWeakReference reference;
        while ((reference = (IdentityWeakReference)
                COLLECTED_IMAGES.poll()) != null) {
            SOURCE_HASHES.remove(reference);
        }
    }

    /** WeakHashMap uses equals/hashCode; mod image subclasses may override both. */
    private static final class IdentityWeakReference
            extends WeakReference<BufferedImage> {
        private final int identityHash;

        private IdentityWeakReference(
                BufferedImage image,
                ReferenceQueue<BufferedImage> queue) {
            super(image, queue);
            identityHash = System.identityHashCode(image);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference reference)) {
                return false;
            }
            BufferedImage image = get();
            return image != null && image == reference.get();
        }
    }

    @FunctionalInterface
    interface DecodedImageLoader {
        BufferedImage decode(byte[] encodedSource) throws IOException;
    }

    private static final class CachedTextureImage extends BufferedImage {
        private final byte[] encodedSource;
        private final TextureConversionCache.CachedTexture cached;
        private final DecodedImageLoader decoder;

        private volatile BufferedImage delegate;
        private volatile boolean invalidated;

        private CachedTextureImage(
                byte[] encodedSource,
                TextureConversionCache.CachedTexture cached,
                DecodedImageLoader decoder) {
            super(1, 1, cached.hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB
                    : BufferedImage.TYPE_INT_RGB);
            this.encodedSource = encodedSource;
            this.cached = cached;
            this.decoder = decoder;
        }

        private TextureConversionCache.CachedTexture cachedTexture() {
            return invalidated ? null : cached;
        }

        private boolean hasAlphaForEngine() {
            return invalidated
                    ? materialize().getColorModel().hasAlpha()
                    : cached.hasAlpha();
        }

        private void invalidate() {
            invalidated = true;
        }

        private BufferedImage invalidateAndMaterialize() {
            BufferedImage materialized = materialize();
            invalidate();
            return materialized;
        }

        private BufferedImage materialize() {
            BufferedImage current = delegate;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = delegate;
                if (current != null) {
                    return current;
                }
                try {
                    current = decoder.decode(encodedSource);
                } catch (IOException failure) {
                    throw new IllegalStateException(
                            "failed to materialize cached texture source",
                            failure);
                }
                if (current == null) {
                    throw new IllegalStateException(
                            "image decoder returned null while materializing"
                                    + " cached texture source");
                }
                delegate = current;
                return current;
            }
        }

        @Override
        public int getWidth() {
            return cached.imageWidth();
        }

        @Override
        public int getHeight() {
            return cached.imageHeight();
        }

        @Override
        public int getWidth(ImageObserver observer) {
            return getWidth();
        }

        @Override
        public int getHeight(ImageObserver observer) {
            return getHeight();
        }

        @Override
        public int getType() {
            return materialize().getType();
        }

        @Override
        public ColorModel getColorModel() {
            return materialize().getColorModel();
        }

        @Override
        public WritableRaster getRaster() {
            invalidate();
            return materialize().getRaster();
        }

        @Override
        public WritableRaster getAlphaRaster() {
            invalidate();
            return materialize().getAlphaRaster();
        }

        @Override
        public int getRGB(int x, int y) {
            return materialize().getRGB(x, y);
        }

        @Override
        public int[] getRGB(
                int startX,
                int startY,
                int width,
                int height,
                int[] rgbArray,
                int offset,
                int scansize) {
            return materialize().getRGB(
                    startX, startY, width, height,
                    rgbArray, offset, scansize);
        }

        @Override
        public void setRGB(int x, int y, int rgb) {
            invalidate();
            materialize().setRGB(x, y, rgb);
        }

        @Override
        public void setRGB(
                int startX,
                int startY,
                int width,
                int height,
                int[] rgbArray,
                int offset,
                int scansize) {
            invalidate();
            materialize().setRGB(
                    startX, startY, width, height,
                    rgbArray, offset, scansize);
        }

        @Override
        public ImageProducer getSource() {
            return materialize().getSource();
        }

        @Override
        public Object getProperty(String name, ImageObserver observer) {
            return materialize().getProperty(name, observer);
        }

        @Override
        public Object getProperty(String name) {
            return materialize().getProperty(name);
        }

        @Override
        public Graphics getGraphics() {
            invalidate();
            return materialize().getGraphics();
        }

        @Override
        public Graphics2D createGraphics() {
            invalidate();
            return materialize().createGraphics();
        }

        @Override
        public BufferedImage getSubimage(
                int x, int y, int width, int height) {
            invalidate();
            return materialize().getSubimage(x, y, width, height);
        }

        @Override
        public boolean isAlphaPremultiplied() {
            return materialize().isAlphaPremultiplied();
        }

        @Override
        public void coerceData(boolean isAlphaPremultiplied) {
            invalidate();
            materialize().coerceData(isAlphaPremultiplied);
        }

        @Override
        public Vector<RenderedImage> getSources() {
            return materialize().getSources();
        }

        @Override
        public String[] getPropertyNames() {
            return materialize().getPropertyNames();
        }

        @Override
        public int getMinX() {
            return materialize().getMinX();
        }

        @Override
        public int getMinY() {
            return materialize().getMinY();
        }

        @Override
        public SampleModel getSampleModel() {
            return materialize().getSampleModel();
        }

        @Override
        public int getNumXTiles() {
            return materialize().getNumXTiles();
        }

        @Override
        public int getNumYTiles() {
            return materialize().getNumYTiles();
        }

        @Override
        public int getMinTileX() {
            return materialize().getMinTileX();
        }

        @Override
        public int getMinTileY() {
            return materialize().getMinTileY();
        }

        @Override
        public int getTileWidth() {
            return materialize().getTileWidth();
        }

        @Override
        public int getTileHeight() {
            return materialize().getTileHeight();
        }

        @Override
        public int getTileGridXOffset() {
            return materialize().getTileGridXOffset();
        }

        @Override
        public int getTileGridYOffset() {
            return materialize().getTileGridYOffset();
        }

        @Override
        public Raster getTile(int tileX, int tileY) {
            invalidate();
            return materialize().getTile(tileX, tileY);
        }

        @Override
        public Raster getData() {
            return materialize().getData();
        }

        @Override
        public Raster getData(Rectangle rectangle) {
            return materialize().getData(rectangle);
        }

        @Override
        public WritableRaster copyData(WritableRaster raster) {
            return materialize().copyData(raster);
        }

        @Override
        public void setData(Raster raster) {
            invalidate();
            materialize().setData(raster);
        }

        @Override
        public void addTileObserver(TileObserver observer) {
            materialize().addTileObserver(observer);
        }

        @Override
        public void removeTileObserver(TileObserver observer) {
            materialize().removeTileObserver(observer);
        }

        @Override
        public boolean isTileWritable(int tileX, int tileY) {
            return materialize().isTileWritable(tileX, tileY);
        }

        @Override
        public Point[] getWritableTileIndices() {
            return materialize().getWritableTileIndices();
        }

        @Override
        public boolean hasTileWriters() {
            return materialize().hasTileWriters();
        }

        @Override
        public WritableRaster getWritableTile(int tileX, int tileY) {
            invalidate();
            return materialize().getWritableTile(tileX, tileY);
        }

        @Override
        public void releaseWritableTile(int tileX, int tileY) {
            materialize().releaseWritableTile(tileX, tileY);
        }

        @Override
        public int getTransparency() {
            return materialize().getTransparency();
        }

        @Override
        public void flush() {
            BufferedImage current = delegate;
            if (current != null) {
                current.flush();
            }
            super.flush();
        }

        @Override
        public String toString() {
            return materialize().toString();
        }
    }
}
