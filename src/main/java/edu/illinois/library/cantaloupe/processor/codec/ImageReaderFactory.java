package edu.illinois.library.cantaloupe.processor.codec;

import edu.illinois.library.cantaloupe.image.Format;
import edu.illinois.library.cantaloupe.source.StreamFactory;
import edu.illinois.library.cantaloupe.source.stream.ClosingMemoryCacheImageInputStream;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class ImageReaderFactory {

    private static final Set<Format> SUPPORTED_FORMATS =
            Collections.unmodifiableSet(EnumSet.of(Format.BMP,
                    Format.GIF, Format.JPG, Format.PNG, Format.TIF, Format.RAFT));

    /**
     * @return Map of available output formats for all known source formats,
     *         based on information reported by ImageIO.
     */
    public static Set<Format> supportedFormats() {
        return SUPPORTED_FORMATS;
    }

    public ImageReader newImageReader(Format format) {
        switch (format) {
            case BMP:
                return new BMPImageReader();
            case GIF:
                return new GIFImageReader();
            case JPG:
                return new JPEGImageReader();
            case PNG:
                return new PNGImageReader();
            case TIF:
                return new TIFFImageReader();
            case RAFT:
                return new RAFTImageReader();
            default:
                throw new IllegalArgumentException("Unsupported format: " + format);
        }
    }

    /**
     * Creates a reusable instance for reading from files.
     *
     * @param sourceFile File to read from.
     * @param format     Format of the source image.
     * @throws IllegalArgumentException if the format is unsupported.
     */
    public ImageReader newImageReader(Path sourceFile,
                                      Format format) throws IOException {
        ImageReader reader = newImageReader(format);
        reader.setSource(sourceFile);
        return reader;
    }

    /**
     * <p>Creates a non-reusable instance.</p>
     *
     * <p>{@link #newImageReader(ImageInputStream, Format)} should be preferred
     * when a first-class {@link ImageInputStream} can be provided.</p>
     *
     * @param inputStream Stream to read from.
     * @param format      Format of the source image.
     * @throws IllegalArgumentException if the format is unsupported.
     */
    public ImageReader newImageReader(InputStream inputStream,
                                      Format format) throws IOException {
        return newImageReader(new ClosingMemoryCacheImageInputStream(inputStream), format);
    }

    /**
     * Creates a non-reusable instance.
     *
     * @param inputStream Stream to read from.
     * @param format      Format of the source image.
     * @throws IllegalArgumentException if the format is unsupported.
     */
    public ImageReader newImageReader(ImageInputStream inputStream,
                                      Format format) throws IOException {
        ImageReader reader = newImageReader(format);
        reader.setSource(inputStream);
        return reader;
    }

    /**
     * Creates a reusable instance for reading from streams.
     *
     * @param streamFactory Source of stream to read from.
     * @param format       Format of the source image.
     * @throws IllegalArgumentException if the format is unsupported.
     */
    public ImageReader newImageReader(StreamFactory streamFactory,
                                      Format format) throws IOException {
        ImageReader reader = newImageReader(format);
        reader.setSource(streamFactory);
        return reader;
    }

}
