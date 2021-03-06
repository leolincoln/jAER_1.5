/*
 * ChipRenderer.java
 *
 * Created on May 2, 2006, 1:49 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */
package net.sf.jaer.graphics;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Iterator;

import net.sf.jaer.chip.AEChip;
import net.sf.jaer.config.ApsDvsConfig;
import net.sf.jaer.event.ApsDvsEvent;
import net.sf.jaer.event.ApsDvsEventPacket;
import net.sf.jaer.event.BasicEvent;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.event.PolarityEvent;
import net.sf.jaer.util.filter.LowpassFilter2d;
import net.sf.jaer.util.histogram.SimpleHistogram;
import eu.seebetter.ini.chips.ApsDvsChip;
import eu.seebetter.ini.chips.DAViS.DAViS240;

/**
 * Class adapted from AEChipRenderer to render not only AE events but also
 * frames.
 *
 * The frame buffer is RGBA so four bytes per pixel. The rendering uses a
 * texture which is a power of two multiple of image size, so watch out for
 * getWidth and getHeight; they return this value and not the number of pixels
 * being rendered from the chip.
 *
 * @author christian, tobi
 * @see ChipRendererDisplayMethod
 */
public class AEFrameChipRenderer extends AEChipRenderer {
    
      /**
     * PropertyChange events
     */
    public static final String EVENT_NEW_FRAME_AVAILBLE = "newFrameAvailable";


    private boolean addedPropertyChangeListener = false;
    public int textureWidth; //due to hardware acceloration reasons, has to be a 2^x with x a natural number
    public int textureHeight; //due to hardware acceloration reasons, has to be a 2^x with x a natural number

    private int sizeX, sizeY, maxADC;
    private int timestamp = 0;
    private LowpassFilter2d lowpassFilter = new LowpassFilter2d();  // 2 lp values are min and max log intensities from each frame
    private float minValue, maxValue;
    public boolean textureRendering = true;
    private float[] onColor, offColor;
    private ApsDvsConfig config;

    /**
     * The linear buffer of RGBA pixel colors of image frame brightness values
     */
    protected FloatBuffer pixBuffer;
    protected FloatBuffer onMap, onBuffer;
    protected FloatBuffer offMap, offBuffer;
    // double buffered histogram so we can accumulate new histogram while old one is still being rendered and returned to caller
    private final int histStep = 4; // histogram bin step in ADC counts of 1024 levels
    private SimpleHistogram adcSampleValueHistogram1 = new SimpleHistogram(0, histStep, (ApsDvsChip.MAX_ADC + 1) / histStep, 0);
    private SimpleHistogram adcSampleValueHistogram2 = new SimpleHistogram(0, histStep, (ApsDvsChip.MAX_ADC + 1) / histStep, 0);
    private SimpleHistogram currentHist = adcSampleValueHistogram1, nextHist = adcSampleValueHistogram2;
    private boolean computeHistograms = false;

    /**
     * PropertyChange
     */
    public static final String AGC_VALUES = "AGCValuesChanged";

    public AEFrameChipRenderer(AEChip chip) {
        super(chip);
        config = (ApsDvsConfig) chip.getBiasgen();
        setAGCTauMs(chip.getPrefs().getFloat("agcTauMs", 1000));
        onColor = new float[4];
        offColor = new float[4];
        resetFrame(0.5f);
    }

    /**
     * Overridden to make gray buffer special for bDVS array
     */
    @Override
    protected void resetPixmapGrayLevel(float value) {
        maxValue = Float.MIN_VALUE;
        minValue = Float.MAX_VALUE;
        checkPixmapAllocation();
        final int n = 4 * textureWidth * textureHeight;
        boolean madebuffer = false;
        if ((grayBuffer == null) || (grayBuffer.capacity() != n)) {
            grayBuffer = FloatBuffer.allocate(n); // BufferUtil.newFloatBuffer(n);
            madebuffer = true;
        }
        if (madebuffer || (value != grayValue)) {
            grayBuffer.rewind();
            for (int y = 0; y < textureWidth; y++) {
                for (int x = 0; x < textureHeight; x++) {
                    if (config.isDisplayFrames()) {
                        grayBuffer.put(0);
                        grayBuffer.put(0);
                        grayBuffer.put(0);
                        grayBuffer.put(1.0f);
                    } else if (colorMode == ColorMode.GrayTime) {
                        grayBuffer.put(1.0f);
                        grayBuffer.put(1.0f);
                        grayBuffer.put(1.0f);
                        grayBuffer.put(1.0f);
                    } else {
                        grayBuffer.put(grayValue);
                        grayBuffer.put(grayValue);
                        grayBuffer.put(grayValue);
                        grayBuffer.put(1.0f);
                    }
                }
            }
        }
        grayBuffer.rewind();
        System.arraycopy(grayBuffer.array(), 0, pixmap.array(), 0, n);
        System.arraycopy(grayBuffer.array(), 0, pixBuffer.array(), 0, n);
        pixmap.rewind();
        pixBuffer.rewind();
        pixmap.limit(n);
        pixBuffer.limit(n);
        setColors();
        resetEventMaps();
    }

    protected void resetEventMaps() {
        setColors();
        checkPixmapAllocation();
        final int n = 4 * textureWidth * textureHeight;
        if ((grayBuffer == null) || (grayBuffer.capacity() != n)) {
            grayBuffer = FloatBuffer.allocate(n); // BufferUtil.newFloatBuffer(n);
        }

        grayBuffer.rewind();
        Arrays.fill(grayBuffer.array(), 0.0f);
        System.arraycopy(grayBuffer.array(), 0, onMap.array(), 0, n);
        System.arraycopy(grayBuffer.array(), 0, offMap.array(), 0, n);

        grayBuffer.rewind();
        onMap.rewind();
        offMap.rewind();
        onMap.limit(n);
        offMap.limit(n);
    }

    @Override
    public synchronized void setColorMode(ColorMode colorMode) {
        super.setColorMode(colorMode);
        setColors();
    }

    private void setColors() {
        checkPixmapAllocation();
        switch (colorMode) {
            case GrayLevel:
            case Contrast:
                onColor[0] = 1.0f;
                onColor[1] = 1.0f;
                onColor[2] = 1.0f;
                onColor[3] = 0.0f;
                offColor[0] = 0.0f;
                offColor[1] = 0.0f;
                offColor[2] = 0.0f;
                offColor[3] = 0.0f;
                break;
            case RedGreen:
            default:
                onColor[0] = 0.0f;
                onColor[1] = 1.0f;
                onColor[2] = 0.0f;
                onColor[3] = 0.0f;
                offColor[0] = 1.0f;
                offColor[1] = 0.0f;
                offColor[2] = 0.0f;
                offColor[3] = 0.0f;
                break;
        }
    }

    private int warningCount = 0;
    private static int WARNING_INTERVAL = 100;

    @Override
    public synchronized void render(EventPacket pkt) {

        if (!addedPropertyChangeListener) {
            if (chip instanceof AEChip) {
                AEChip aeChip = chip;
                if (aeChip.getAeViewer() != null) {
                    aeChip.getAeViewer().addPropertyChangeListener(this);
                    addedPropertyChangeListener = true;
                }
            }
        }
        if (!(pkt instanceof ApsDvsEventPacket)) {
            if ((warningCount++ % WARNING_INTERVAL) == 0) {
                log.info("I only know how to render ApsDvsEventPacket but got " + pkt);
            }
            resetEventMaps();
            resetFrame(0);
            return;
        }

        if (getChip() instanceof DAViS240) {
            computeHistograms = ((DAViS240) chip).isShowImageHistogram() || ((ApsDvsChip) chip).isAutoExposureEnabled();
        }

        ApsDvsEventPacket packet = (ApsDvsEventPacket) pkt;

        checkPixmapAllocation();
        resetSelectedPixelEventCount(); // TODO fix locating pixel with xsel ysel

        this.packet = packet;
        if (!(packet.getEventPrototype() instanceof ApsDvsEvent)) {
            if ((warningCount++ % WARNING_INTERVAL) == 0) {
                log.warning("wrong input event class, got " + packet.getEventPrototype() + " but we need to have " + ApsDvsEvent.class);
            }
            return;
        }
        if (!accumulateEnabled) {
//            resetFrame(0.5f);
            resetEventMaps();
        }
        boolean displayEvents = config.isDisplayEvents(),
                displayFrames = config.isDisplayFrames(),
                paused = chip.getAeViewer().isPaused(), backwards = packet.getDurationUs() < 0;

        Iterator allItr = packet.fullIterator();
        setSpecialCount(0);
        while (allItr.hasNext()) {
            //The iterator only iterates over the DVS events
            ApsDvsEvent e = (ApsDvsEvent) allItr.next();
            if (e.isSpecial()) {
                setSpecialCount(specialCount + 1); // TODO optimize special count increment
                continue;
            }
            int type = e.getType();
            boolean isAdcSampleFlag = e.isSampleEvent();
            if (!isAdcSampleFlag) {
                if (displayEvents) {
                    if ((xsel >= 0) && (ysel >= 0)) { // find correct mouse pixel interpretation to make sounds for large pixels
                        int xs = xsel, ys = ysel;
                        if ((e.x == xs) && (e.y == ys)) {
                            playSpike(type);
                        }
                    }
                    updateEventMaps(e);
                }
            } else if (!backwards && isAdcSampleFlag && displayFrames && !paused) { // TODO need to handle single step updates here
                updateFrameBuffer(e);
            }
        }
//        pixmap.rewind();
//        pixBuffer.rewind();
//        onMap.rewind();
//        offMap.rewind();
    }

    private void updateFrameBuffer(ApsDvsEvent e) {
        float[] buf = pixBuffer.array();
        // TODO if playing backwards, then frame will come out white because B sample comes before A
        if (e.isStartOfFrame()) {
            startFrame(e.timestamp);
        } else if (e.isResetRead()) {
            int index = getIndex(e);
            if ((index < 0) || (index >= buf.length)) {
                return;
            }
            float val = e.getAdcSample();
            buf[index] = val;
            buf[index + 1] = val;
            buf[index + 2] = val;
        } else if (e.isSignalRead()) {
            int index = getIndex(e);
            if ((index < 0) || (index >= buf.length)) {
                return;
            }
            int val = ((int) buf[index] - e.getAdcSample());
            if (val < minValue) {
                minValue = val;
            } else if (val > maxValue) {
                maxValue = val;
            }
            // right here sample-reset value of this pixel is in val

            if (computeHistograms) {
                nextHist.add(val);
            }
            float fval = normalizeFramePixel(val);
//            fval=.5f;
            buf[index] = fval;
            buf[index + 1] = fval;
            buf[index + 2] = fval;
            buf[index + 3] = 1;
        } else if (e.isEndOfFrame()) {
            endFrame();
            SimpleHistogram tmp = currentHist;
            if (computeHistograms) {
                currentHist = nextHist;
                nextHist = tmp;
                nextHist.reset();
            }
            ((ApsDvsChip) chip).controlExposure();

        }
    }

    private void startFrame(int ts) {
        timestamp = ts;
        maxValue = Float.MIN_VALUE;
        minValue = Float.MAX_VALUE;
        System.arraycopy(grayBuffer.array(), 0, pixBuffer.array(), 0, pixBuffer.array().length);

    }

    private void endFrame() {
        System.arraycopy(pixBuffer.array(), 0, pixmap.array(), 0, pixBuffer.array().length);
        if ((minValue > 0) && (maxValue > 0)) { // don't adapt to first frame which is all zeros
            java.awt.geom.Point2D.Float filter2d = lowpassFilter.filter2d(minValue, maxValue, timestamp);
            getSupport().firePropertyChange(AGC_VALUES, null, filter2d); // inform listeners (GUI) of new AGC min/max filterd log intensity values
        }
        getSupport().firePropertyChange(EVENT_NEW_FRAME_AVAILBLE,null,this); // TODO document what is sent and send something reasonable
    }

    /**
     * changes alpha of ON map
     *
     * @param index 0-(size of pixel array-1) of pixel
     */
    private void updateEventMaps(ApsDvsEvent e) {
        float[] map;
        int index = getIndex(e);
        if (e.polarity == ApsDvsEvent.Polarity.On) {
            map = onMap.array();
        } else {
            map = offMap.array();
        }
        if ((index < 0) || (index >= map.length)) {
            return;
        }
        if (colorMode == ColorMode.ColorTime) {
            int ts0 = packet.getFirstTimestamp();
            float dt = packet.getDurationUs();
            int ind = (int) Math.floor(((NUM_TIME_COLORS - 1) * (e.timestamp - ts0)) / dt);
            if (ind < 0) {
                ind = 0;
            } else if (ind >= timeColors.length) {
                ind = timeColors.length - 1;
            }
            map[index] = timeColors[ind][0];
            map[index + 1] = timeColors[ind][1];
            map[index + 2] = timeColors[ind][2];
            map[index + 3] = 0.5f;
        } else if (colorMode == ColorMode.GrayTime) {
            int ts0 = packet.getFirstTimestamp();
            float dt = packet.getDurationUs();
            float v = 0.95f - (0.95f * ((e.timestamp - ts0) / dt));
            map[index] = v;
            map[index + 1] = v;
            map[index + 2] = v;
            map[index + 3] = 1.0f;
        } else {
            float alpha = map[index + 3] + (1.0f / colorScale);
            alpha = normalizeEvent(alpha);
            if ((e.polarity == PolarityEvent.Polarity.On) || ignorePolarityEnabled) {
                map[index] = onColor[0];
                map[index + 1] = onColor[1];
                map[index + 2] = onColor[2];
            } else {
                map[index] = offColor[0];
                map[index + 1] = offColor[1];
                map[index + 2] = offColor[2];
            }
            map[index + 3] = alpha;
        }
    }

    final int INTERVAL_BETWEEEN_OUT_OF_BOUNDS_EXCEPTIONS_PRINTED_MS = 1000;
    private long lastWarningPrintedTimeMs = Integer.MAX_VALUE;

    private int getIndex(BasicEvent e) {
        int x = e.x, y = e.y;
        if ((x < 0) || (y < 0) || (x >= sizeX) || (y >= sizeY)) {
            if ((System.currentTimeMillis() - lastWarningPrintedTimeMs) > INTERVAL_BETWEEEN_OUT_OF_BOUNDS_EXCEPTIONS_PRINTED_MS) {
                log.warning(String.format("Event %s out of bounds and cannot be rendered in bounds sizeX=%d sizeY=%d - delaying next warning for %dms", e.toString(), sizeX, sizeY, INTERVAL_BETWEEEN_OUT_OF_BOUNDS_EXCEPTIONS_PRINTED_MS));
                lastWarningPrintedTimeMs = System.currentTimeMillis();
            }
            return -1;
        }
        if (textureRendering) {
            return 4 * (x + (y * textureWidth));
        } else {
            return 4 * (x + (y * sizeX));
        }
    }

    @Override
    protected void checkPixmapAllocation() {
        if ((sizeX != chip.getSizeX()) || (sizeY != chip.getSizeY())) {
            sizeX = chip.getSizeX();
            textureWidth = ceilingPow2(sizeX);
            sizeY = chip.getSizeY();
            textureHeight = ceilingPow2(sizeY);
        }
        final int n = 4 * textureWidth * textureHeight;
        if ((pixmap == null) || (pixmap.capacity() < n) || (pixBuffer.capacity() < n) || (onMap.capacity() < n) || (offMap.capacity() < n)) {
            pixmap = FloatBuffer.allocate(n); // BufferUtil.newFloatBuffer(n);
            pixBuffer = FloatBuffer.allocate(n);
            onMap = FloatBuffer.allocate(n);
            offMap = FloatBuffer.allocate(n);
        }
    }

    /**
     * Overrides color scale setting to NOT update the stored accumulated pixmap
     * when the color scale is changed.
     *
     *
     */
    @Override
    synchronized public void setColorScale(int colorScale) {
        int old = this.colorScale;
        if (colorScale < 1) {
            colorScale = 1;
        }
        if (colorScale > 128) {
            colorScale = 128;
        }
        this.colorScale = colorScale;
        prefs.putInt("Chip2DRenderer.colorScale", colorScale);
        getSupport().firePropertyChange(EVENT_COLOR_SCALE_CHANGE, old, colorScale);
    }

    private static int ceilingPow2(int n) {
        int pow2 = 1;
        while (n > pow2) {
            pow2 = pow2 << 1;
        }
        return pow2;
    }

    /**
     * Returns pixmap for ON events
     *
     * @return a float buffer. Obtain a pixel from it using getPixMapIndex
     * @see #getPixMapIndex(int, int)
     */
    public FloatBuffer getOnMap() {
        onMap.rewind();
        checkPixmapAllocation();
        return onMap;
    }

    /**
     * Returns pixmap for OFF events
     *
     * @return a float buffer. Obtain a pixel from it using getPixMapIndex
     * @see #getPixMapIndex(int, int)
     */
    public FloatBuffer getOffMap() {
        offMap.rewind();
        checkPixmapAllocation();
        return offMap;
    }

    /**
     * Returns index into pixmap
     *
     * @param x
     * @param y
     * @return the index
     */
    @Override
    public int getPixMapIndex(int x, int y) {
        if (textureRendering) {
            return 4 * (x + (y * textureWidth));
        } else {
            return 3 * (x + (y * sizeX));
        }
    }

    /**
     * Overridden to return ON and OFF map values as R and G channels. B channel
     * is returned 0. Note that this method returns rendering of events; it
     * disregards APS frame values.
     *
     * @param x
     * @param y
     * @return
     */
    public float[] getDvsRenderedValuesAtPixel(int x, int y) {
        int k = getPixMapIndex(x, y);
        float[] f = new float[3];
        f[0] = onMap.get(k + 3);
        f[1] = offMap.get(k + 3);
        f[2] = 0; // return alpha channel which is the ON and OFF value that is rendered (RGB are 1 for ON and OFF maps)
        return f; //To change body of generated methods, choose Tools | Templates.
    }

    /**
     * Overridden to combine ON and OFF map values to a gray value by averaging
     * them. Note that this method returns rendering of events; it disregards
     * APS frame values.
     *
     * @param x
     * @param y
     * @return
     */
    public float getApsGrayValueAtPixel(int x, int y) {
        int k=getPixMapIndex(x, y);
        float[] pm=pixmap.array();
        return (pm[k] + pm[k+1] + pm[k+2]) / 3;
    }

    /**
     * Returns the buffer holding the image frame brightness values in RGBA
     * order
     */
    public FloatBuffer getPixBuffer() {
        return pixBuffer;
    }

    /**
     * Returns the width of the texture used to render output. Note this is NOT
     * the chip dimension; it is a power of 2 multiple that is next larger to
     * chip size.
     *
     * @return power of 2 multiple that is next larger to chip size
     */
    @Override
    public int getWidth() {
        return textureWidth;
    }

    /**
     * Returns the height of the texture used to render output. Note this is NOT
     * the chip dimension; it is a power of 2 multiple that is next larger to
     * chip size.
     *
     * @return power of 2 multiple that is next larger to chip size
     */
    @Override
    public int getHeight() {
        return textureHeight;
    }

    /**
     * Computes the normalized gray value from an ADC sample value using
     * brightness (offset), contrast (multiplier), and gamma (power law). Takes
     * account of the autoContrast setting which attempts to set value
     * automatically to get image in range of display.
     *
     * @param value the ADC value
     * @return the gray value
     */
    private float normalizeFramePixel(float value) {
        float v;
        if (!config.isUseAutoContrast()) { // fixed rendering computed here
            float gamma = config.getGamma();
            if (gamma == 1.0f) {
                v = ((config.getContrast() * value) + config.getBrightness()) / maxADC;
            } else {
                v = (float) (Math.pow((((config.getContrast() * value) + config.getBrightness()) / maxADC), gamma));
            }
        } else {
            java.awt.geom.Point2D.Float filter2d = lowpassFilter.getValue2d();
            float offset = filter2d.x;
            float range = (filter2d.y - filter2d.x);
            v = ((value - offset)) / (range);
//           System.out.println("offset="+offset+" range="+range+" value="+value+" v="+v);
        }
        if (v < 0) {
            v = 0;
        } else if (v > 1) {
            v = 1;
        }
        return v;
    }

    private float normalizeEvent(float value) {
        if (value < 0) {
            value = 0;
        } else if (value > 1) {
            value = 1;
        }
        return value;
    }

    public float getAGCTauMs() {
        return lowpassFilter.getTauMs();
    }

    public void setAGCTauMs(float tauMs) {
        if (tauMs < 10) {
            tauMs = 10;
        }
        lowpassFilter.setTauMs(tauMs);
        chip.getPrefs().putFloat("agcTauMs", tauMs);
    }

    public void applyAGCValues() {
        java.awt.geom.Point2D.Float f = lowpassFilter.getValue2d();
        config.setBrightness(agcOffset());
        config.setContrast(agcGain());
    }

    private int agcOffset() {
        return (int) lowpassFilter.getValue2d().x;
    }

    private int agcGain() {
        java.awt.geom.Point2D.Float f = lowpassFilter.getValue2d();
        float diff = f.y - f.x;
        if (diff < 1) {
            return 1;
        }
        int gain = (int) (maxADC / (f.y - f.x));
        return gain;
    }

    public int getMaxADC() {
        return maxADC;
    }

    public void setMaxADC(int max) {
        maxADC = max;
    }

    public void resetAutoContrast() {

    }

    /**
     * @return the gray level of the rendered data; used to determine whether a
     * pixel needs to be drawn
     */
    @Override
    public float getGrayValue() {
        if (config.isDisplayFrames() || (colorMode == ColorMode.Contrast) || (colorMode == ColorMode.GrayLevel)) {
            grayValue = 0.5f;
        } else if (colorMode == ColorMode.GrayTime) {
            grayValue = 1.0f;
        } else {
            grayValue = 0.0f;
        }
        return this.grayValue;
    }

    public boolean isDisplayFrames() {
        return config.isDisplayFrames();
    }

    public boolean isDisplayEvents() {
        return config.isDisplayEvents();
    }

    /**
     * Returns the current valid histogram of ADC sample values. New ADC values
     * are accumulated to another histogram and the histograms are swapped when
     * a new frame has finished being captured.
     *
     * @return the adcSampleValueHistogram
     */
    public SimpleHistogram getAdcSampleValueHistogram() {
        return currentHist;
    }

    public int getWidthInPixels() {
        return getWidth();
    }

    public int getHeightInPixels() {
        return getHeight();
    }

}
