package io.github.cawfree.accelerometerfft;

import android.content.Context;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;

/** A class which demonstrates the Fourier Transform using accelerometer data. */
public final class MainActivity extends AppCompatActivity implements SensorEventListener {

    /* Static Declarations. */
    private static final int WINDOW_LENGTH = 512;

    /** FFT Runnable; used to compute Fourier Transform on a shared buffer of data. */
    private final class FourierRunnable implements Runnable {
        /* Member Variables. */
        private final double[][] mSampleBuffer;
        private final double[][] mResultBuffer;
        private       boolean    mReady;
        private       float      mSampleRate;
        /** Constructor. */
        public FourierRunnable(final double[][] pSampleBuffer) {
            // Initialize Member Variables.
            this.mSampleBuffer = pSampleBuffer;
            this.mResultBuffer = new double[][] {
                // Storing two components; Magnitude _and_ Frequency.
                new double[MainActivity.WINDOW_LENGTH * 2],
                new double[MainActivity.WINDOW_LENGTH * 2],
                new double[MainActivity.WINDOW_LENGTH * 2],
            };
            this.mSampleRate   = -1.0f;
        }
        /** Defines Computation. */
        @Override public final void run() {
            // Do forever.
            while(true) { /** TODO: Extern control */
                // Synchronize along the SampleBuffer.
                synchronized(this.getSampleBuffer()) {
                    // Assert that we're ready for new samples.
                    this.setReady(true);
                    // Wait to be notified for when the SampleBuffer is ready.
                    try { this.getSampleBuffer().wait(); } catch (InterruptedException pInterruptedException) { pInterruptedException.printStackTrace(); }
                    // Assert that we're in the middle of processing, and therefore no longer ready.
                    this.setReady(false);
                }
                // Declare the SampleBuffer.
                final double[]     lFFT       = new double[MainActivity.WINDOW_LENGTH * 2];
                // Allocate the FFT.
                final DoubleFFT_1D lDoubleFFT = new DoubleFFT_1D(MainActivity.WINDOW_LENGTH);
                // Iterate the axis. (Limit to X only.)
                for(int i = 0; i < 3; i++) {
                    // Fetch the sampled data.
                    final double[] lSamples = this.getSampleBuffer()[i];
                    // Copy over the Samples.
                    System.arraycopy(lSamples, 0, lFFT, 0, lSamples.length);
                    // Parse the FFT.
                    lDoubleFFT.realForwardFull(lFFT);
                    // Iterate the results. (Real/Imaginary components are interleaved.) (Ignoring the first harmonic.)
                    for(int j = 0; j < lFFT.length; j += 2) {
                        // Fetch the Real and Imaginary Components.
                        final double  lRe                = lFFT[j + 0];
                        final double  lIm                = lFFT[j + 1];
                        // Calculate the Magnitude, in decibels, of this current signal index.
                        final double  lMagnitude         = 20.0 * Math.log10(Math.sqrt((lRe*lRe) + (lIm*lIm)) / lSamples.length);
                        // Calculate the frequency at this magnitude.
                        final double  lFrequency         = j * this.getSampleRate() / lFFT.length;
                        // Update the ResultBuffer.
                        this.getResultBuffer()[i][j + 0] = lMagnitude;
                        this.getResultBuffer()[i][j + 1] = lFrequency;
                    }
                }
                // Update the Callback.
                MainActivity.this.onFourierResult(this.getResultBuffer(), this.getSampleRate());
            }
        }
        /* Getters. */
        private final double[][] getSampleBuffer() {
            return this.mSampleBuffer;
        }
        private final double[][] getResultBuffer() {
            return this.mResultBuffer;
        }
        private final void setReady(final boolean pIsReady) {
            this.mReady = pIsReady;
        }
        public final boolean isReady() {
            return this.mReady;
        }
        protected final void setSampleRate(final float pSampleRate) {
            this.mSampleRate = pSampleRate;
        }
        public final float getSampleRate() {
            return this.mSampleRate;
        }
    }

    /* Member Variables. */
    private SensorManager   mSensorManager;
    private double[][]      mSampleWindows;
    private double[][]      mDecoupler;
    private FourierRunnable mFourierRunnable;
    private int             mOffset;
    private long            mTimestamp;

    @Override
    protected final void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Set the Content View.
        this.setContentView(R.layout.activity_main);
        // Fetch the SensorManager.
        this.mSensorManager   = (SensorManager)this.getSystemService(Context.SENSOR_SERVICE);
        this.mSampleWindows   = new double[][] {
                // X, Y, Z.
                new double[MainActivity.WINDOW_LENGTH],
                new double[MainActivity.WINDOW_LENGTH],
                new double[MainActivity.WINDOW_LENGTH],
        };
        this.mDecoupler       = new double[][] {
                // X, Y, Z.
                new double[MainActivity.WINDOW_LENGTH],
                new double[MainActivity.WINDOW_LENGTH],
                new double[MainActivity.WINDOW_LENGTH],
        };
        // Declare the FourierRunnable.
        this.mFourierRunnable = new FourierRunnable(this.getDecoupler());
        // Initialize the Timestamp.
        this.mTimestamp       = -1L;
        // Start the FourierRunnable.
        (new Thread(this.getFourierRunnable())).start();
    }

    @Override
    protected final void onResume() {
        // Implement the Parent.
        super.onResume();
        // Listen for accelerometer data.
        this.getSensorManager().registerListener(this, this.getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME);
        // Initialize the Offset and Timestamp.
        this.setOffset(0);
        this.setTimestamp(System.nanoTime());
    }

    @Override
    protected final void onPause() {
        // Implement the Parent.
        super.onPause();
        // Stop listening for acceleromter data.
        this.getSensorManager().unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        // Are we handling the accelerometer?
        if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Buffer the SensorEvent data.
            for(int i = 0; i < sensorEvent.values.length; i++) {
                // Update the SampleWindows.
                this.getSampleWindows()[i][this.getOffset()] = sensorEvent.values[i];
            }
            // Increase the Offset.
            this.setOffset(this.getOffset() + 1);
            // Is the buffer full?
            if(this.getOffset() == MainActivity.WINDOW_LENGTH) {
                // Is the FourierRunnable ready?
                if(this.getFourierRunnable().isReady()) {
                    // Fetch the Timestamp.
                    final long  lTimestamp = System.nanoTime();
                    // Convert the difference in time into the corresponding time in seconds.
                    final float lDelta     = (float)((lTimestamp - this.getTimestamp()) * (0.0000000001));
                    // Determine the Sample Rate.
                    final float lFs        = 1.0f / (lDelta / MainActivity.WINDOW_LENGTH);
                    // Provide the FourierRunnable with the Sample Rate.
                    this.getFourierRunnable().setSampleRate(lFs);
                    // Copy over the buffered data.
                    for(int i = 0; i < this.getSampleWindows().length; i++) {
                        // Copy the data over to the shared buffer.
                        System.arraycopy(this.getSampleWindows()[i], 0, this.getDecoupler()[i], 0, this.getSampleWindows()[i].length);
                    }
                    // Synchronize along the Decoupler.
                    synchronized(this.getDecoupler()) {
                        // Notify any listeners. (There should be one; the FourierRunnable!)
                        this.getDecoupler().notify();
                    }
                }
                else {
                    // Here, we've wasted an entire frame of accelerometer data.
                    Log.d("TB/API", "Wasted samples.");
                }
                // Reset the Offset.
                this.setOffset(0);
                // Re-initialize the Timestamp.
                this.setTimestamp(System.nanoTime());
            }
        }
    }

    /** Called when the application has returned the lastest FFT results, for each axis, in the interleaved form Magnitude, Frequency. */
    public final void onFourierResult(final double[][] pResultBuffer, final double pSampleRate) {
        // Linearize execution.
        this.runOnUiThread(new Runnable() { @Override public final void run() {
            final int[]         lIds      = new int[] { R.id.lc_x, R.id.lc_y, R.id.lc_z };
            for(int i = 0; i < 3; i++) {
                final double[] lResult = pResultBuffer[i];
                final List<Entry> lList = new ArrayList();
                LineChart lLineChart = ((LineChart)MainActivity.this.findViewById(lIds[i]));
                for(int j = 0; j < lResult.length/16; j += 2) {
                    lList.add(new Entry((float)pResultBuffer[i][j + 1], (float)pResultBuffer[i][j + 0]));
                }
                Log.d("TB/API", "list size is "+lList.size());
                LineDataSet dataset = new LineDataSet(lList, "# of Calls");
                dataset.setColor(Color.RED);
                lLineChart.setAutoScaleMinMaxEnabled(false);
                lLineChart.setData(new LineData(dataset));
                lLineChart.invalidate();
            }
        } });
    }

    /* Unused overrides. */
    @Override public final void onAccuracyChanged(final Sensor pSensor, final int pType) { }

    /* Getters. */
    private final SensorManager getSensorManager() {
        return this.mSensorManager;
    }

    private final double[][] getSampleWindows() {
        return this.mSampleWindows;
    }

    private final double[][] getDecoupler() {
        return this.mDecoupler;
    }

    private final FourierRunnable getFourierRunnable() {
        return this.mFourierRunnable;
    }

    private final void setOffset(final int pOffset) {
        this.mOffset = pOffset;
    }

    private final int getOffset() {
        return this.mOffset;
    }

    private final void setTimestamp(final long pTimestamp) {
        this.mTimestamp = pTimestamp;
    }

    private final long getTimestamp() {
        return this.mTimestamp;
    }

}
