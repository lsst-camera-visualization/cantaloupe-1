package edu.illinois.library.cantaloupe.processor.codec;

import edu.illinois.library.cantaloupe.image.Compression;
import edu.illinois.library.cantaloupe.image.Dimension;
import edu.illinois.library.cantaloupe.image.Metadata;
import edu.illinois.library.cantaloupe.image.Rectangle;
import edu.illinois.library.cantaloupe.image.ScaleConstraint;
import edu.illinois.library.cantaloupe.operation.Crop;
import edu.illinois.library.cantaloupe.operation.CropByPercent;
import edu.illinois.library.cantaloupe.operation.ReductionFactor;
import edu.illinois.library.cantaloupe.operation.Scale;
import edu.illinois.library.cantaloupe.source.StreamFactory;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * An image reader which works by delegating to one or more other cantaloupe
 * servers.
 *
 * @author tonyj
 */
final class DelegatingImageReader implements ImageReader {

    private static final Logger LOGGER
            = LoggerFactory.getLogger(DelegatingImageReader.class);
    private String imageId;
    private List<Server> servers;
    private double maxX;
    private double maxY;

    @Override
    public Compression getCompression(int imageIndex) {
        return Compression.UNCOMPRESSED;
    }

    @Override
    public Metadata getMetadata(int imageIndex) throws IOException {
        return null;
    }

    @Override
    public boolean canSeek() {
        return true;
    }

    @Override
    public int getNumResolutions() throws IOException {
        return 10; // FIXME
    }

    @Override
    public BufferedImage read(final int imageIndex,
                              final Crop crop,
                              final Scale scale,
                              final ScaleConstraint scaleConstraint,
                              final ReductionFactor reductionFactor,
                              final Set<ReaderHint> hints) throws IOException {

        LOGGER.info("CANTALOUPE: ReductionFactor=" + reductionFactor);
        LOGGER.info("CANTALOUPE: hints=" + hints);

        LOGGER.info("CANTALOUPE: scale=" + scale);
        LOGGER.info("CANTALOUPE: crop=" + crop);
        final Dimension fullSize = getSize(0);
        Crop actualCrop = crop == null ? new CropByPercent() : crop;
        final Rectangle regionRect = actualCrop.getRectangle(fullSize, new ReductionFactor(), scaleConstraint);
        LOGGER.info("CANTALOUPE: regionRect=" + regionRect);
        Dimension resultingSize = scale.getResultingSize(fullSize, reductionFactor, scaleConstraint);
        LOGGER.info("CANTALOUPE: resultingSize=" + resultingSize);
        Rectangle2D region = new Rectangle2D.Double(regionRect.x(), regionRect.y(), regionRect.width(), regionRect.height());
        BufferedImage result = new BufferedImage(resultingSize.intWidth(), resultingSize.intHeight(), BufferedImage.TYPE_INT_RGB);
        hints.add(ReaderHint.ALREADY_CROPPED);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        boolean odd = true;
        for (Server server : servers) {
            odd = !odd;
            final boolean  oddd = odd;
            if (server.getDestinationRectangle().intersects(region)) {

                // Intersection in image coordinates
                Rectangle2D intersection = server.getDestinationRectangle().createIntersection(region);
                LOGGER.info("CANTALOUPE: intersection=" + intersection);
                // intersection in destination coordinates
                Rectangle2D destinationIntersection = new Rectangle2D.Double(
                        intersection.getX() - server.getDestinationRectangle().getX(),
                        intersection.getY() - server.getDestinationRectangle().getY(),
                        intersection.getWidth(), intersection.getHeight());
                Point2D requestSize = new Point2D.Double(resultingSize.width() * intersection.getWidth() / region.getWidth(), resultingSize.height() * intersection.getHeight() / region.getHeight());
                LOGGER.info("CANTALOUPE: intersection,requestSize=" + intersection+","+requestSize);

                CompletableFuture<BufferedImage> futureImage = server.read(imageId, destinationIntersection, requestSize);
                futures.add(futureImage.thenAccept(img -> {
                    LOGGER.info("CANTALOUPE: got image=" + img);
                    Graphics2D g2 = result.createGraphics();
                    try {
                        g2.scale(resultingSize.width() / region.getWidth(), resultingSize.height() / region.getHeight());
                        g2.translate(-region.getX(), -region.getY());
                        g2.drawImage(img, (int) intersection.getX(), (int) intersection.getY(), (int) intersection.getWidth(), (int) intersection.getHeight(), null);
//                        if (oddd) {
//                            g2.setColor(new Color(255,0,0,64));
//                            g2.fillRect((int) intersection.getX(), (int) intersection.getY(), (int) intersection.getWidth(), (int) intersection.getHeight());
//                        }
                    } finally {
                        g2.dispose();
                    }
                }));
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).join();
        return result;
    }

    @Override
    public BufferedImage read(int imageIndex) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setSource(ImageInputStream inputStream) throws IOException {
        LOGGER.info("CANTALOUPE: setSource");
    }

    @Override
    public void setSource(StreamFactory streamFactory) throws IOException {
        LOGGER.info("CANTALOUPE: setSourceFactory");
    }

    @Override
    public void setSource(Path inputFile) throws IOException {
        LOGGER.info("CANTALOUPE: setSourcePath " + inputFile);
        List<String> lines = Files.readAllLines(inputFile);
        imageId = lines.get(0);
        servers = lines.subList(1, lines.size()).stream()
                .filter(line -> !line.startsWith("#"))
                .map(line -> new Server(line))
                .collect(Collectors.toList());
        if (servers.size() < 1) {
            throw new IOException("No servers specified");
        }
        // Compute total image size
        maxX = servers.stream()
                .map(server -> server.getDestinationRectangle())
                .mapToDouble(rect -> rect.getX() + rect.getWidth())
                .max().getAsDouble();
        maxY = servers.stream()
                .map(server -> server.getDestinationRectangle())
                .mapToDouble(rect -> rect.getY() + rect.getHeight())
                .max().getAsDouble();
        LOGGER.info("CANTALOUPE: " + imageId + " " + servers + " " + maxX + " " + maxY);
    }

    @Override
    public void dispose() {
    }

    @Override
    public int getNumImages() throws IOException {
        return 1;
    }

    @Override
    public Dimension getSize(int imageIndex) throws IOException {
        return new Dimension(maxX, maxY);
    }

    @Override
    public Dimension getTileSize(int imageIndex) throws IOException {
        return new Dimension(maxX/25, maxY/25);
    }

    @Override
    public RenderedImage readRendered(int imaageIndex, Crop crop, Scale scale, ScaleConstraint scaleConstraint, ReductionFactor reductionFactor, Set<ReaderHint> hints) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public BufferedImageSequence readSequence() throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    private static class Server {

        private static final Pattern PATTERN = Pattern.compile("(.+)=\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]");
        private final URL baseURL;
        private final Rectangle2D sourceRectangle;
        private Rectangle2D destinationRectangle;
        private double xscale;
        private double yscale;

        /**
         * Parses a line from read from the input file, of the form:
         * <server-url>=[source-rectangle] [destination-rectangle]
         *
         * @param line The line to parse
         */
        Server(String line) {
            Matcher match = PATTERN.matcher(line.replaceAll("\\s+", ""));
            if (!match.matches()) {
                throw new IllegalArgumentException("Invalid line: " + line);
            }
            try {
                this.baseURL = new URL(match.group(1));
                this.sourceRectangle = new Rectangle2D.Double(
                        Integer.parseInt(match.group(2)),
                        Integer.parseInt(match.group(3)),
                        Integer.parseInt(match.group(4)),
                        Integer.parseInt(match.group(5))
                );
                this.destinationRectangle = new Rectangle2D.Double(
                        Integer.parseInt(match.group(6)),
                        Integer.parseInt(match.group(7)),
                        Integer.parseInt(match.group(8)),
                        Integer.parseInt(match.group(9))
                );
                xscale = destinationRectangle.getWidth() / sourceRectangle.getWidth();
                yscale = destinationRectangle.getHeight() / sourceRectangle.getHeight();
            } catch (MalformedURLException | NumberFormatException x) {
                throw new IllegalArgumentException("Invalid line: " + line, x);
            }
        }

        private URL getBaseURL() {
            return baseURL;
        }

        public Rectangle2D getSourceRectangle() {
            return sourceRectangle;
        }

        public Rectangle2D getDestinationRectangle() {
            return destinationRectangle;
        }

        public double getXscale() {
            return xscale;
        }

        public double getYscale() {
            return yscale;
        }

        @Override
        public String toString() {
            return "Server{" + "baseURL=" + baseURL + ", sourceRectangle=" + sourceRectangle + ", destinationRectangle=" + destinationRectangle + '}';
        }

        private CompletableFuture<BufferedImage> read(String imageId, Rectangle2D region, Point2D requestSize) throws IOException {
            CantaloupeClient cc = new CantaloupeClient(baseURL);
            Point2D sourceXY = new Point2D.Double(sourceRectangle.getX() + region.getX() / xscale, sourceRectangle.getY() + region.getY() / yscale);
            Point2D sourceSize = new Point2D.Double(region.getWidth() / xscale, region.getHeight() / yscale);
            //Point2D requestSize = new Point2D.Double(region.getWidth() * imageScale.getX(), region.getHeight() * imageScale.getY());
            return cc.read(imageId, sourceXY, sourceSize, requestSize, "png");
        }
    }

    private static class CantaloupeClient {

        private final URL base;
        private static final OkHttpClient client = new OkHttpClient();

        CantaloupeClient(URL base) {
            this.base = base;
        }

        CompletableFuture<BufferedImage> read(String imageId, Point2D sourceXY, Point2D sourceSize, Point2D size, String format) throws IOException {
            String regionString = String.format("%d,%d,%d,%d/", (int) sourceXY.getX(), (int) sourceXY.getY(), (int) sourceSize.getX(), (int) sourceSize.getY());
            String sizeString = String.format("%d,%d/", (int) size.getX(), (int) size.getY());
            URI fullURI;
            try {
                fullURI = base.toURI().resolve(imageId + "/").resolve(regionString).resolve(sizeString).resolve("./0/default." + format);
                LOGGER.info("CANTALOUPE: fetching " + fullURI);
                CompletableFuture<BufferedImage> future = new CompletableFuture<>();
                Request request = new Request.Builder().url(fullURI.toURL()).build();
                Call call = client.newCall(request);
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException x) {
                       future.completeExceptionally(x);
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try (InputStream responseStream = response.body().byteStream()) {
                            future.complete(ImageIO.read(responseStream));
                        } catch (IOException x) {
                            future.completeExceptionally(x);
                        }
                    }
                });
                return future;
            } catch (URISyntaxException x) {
                throw new IOException("Error fetching image", x);
            }
        }

    }
}
