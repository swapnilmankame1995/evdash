/*
 * This work is a derivative of the GeoView code from the Tricorder project http://code.google.com/p/moonblink/wiki/Tricorder
 * under the GNU GPL v2 license.
 */
package gps;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.util.Log;

import autorad.android.C;
import autorad.android.sensor.DataType;
import autorad.android.sensor.SensorDataListener;
import autorad.android.sensor.SensorDataSource;
import autorad.android.transport.DataStatus;
import autorad.android.transport.DataStatusChangeListener;
import autorad.android.transport.SourceType;
import autorad.android.util.Convert;


/**
 * A view which displays geographical data.
 */
public class GeoLocator
        implements SensorDataSource, LocationListener, GpsStatus.Listener, SensorEventListener
{


    // Time in ms for which cached satellite data is valid.
    private static final int DATA_CACHE_TIME = 2 * 1000;  // was 10

    // Time in ms for which cached geomagnetic data is valid.
    private static final int GEOMAG_CACHE_TIME = 2 * 3600 * 1000;

   
    // ******************************************************************** //
    // Private Data.
    // ******************************************************************** //

    // Application handle.
    private Context appContext;

    LocationProvider prov;
    
    private DataStatusChangeListener statusListener;
    
	private ArrayList<SensorDataListener> listeners = new ArrayList<SensorDataListener>();
	private Lock lock = new ReentrantLock();
    
    // The sensor manager, which we use to interface to all sensors.
    private SensorManager sensorManager;

    // The location manager, from which we get location updates.
    private LocationManager locationManager;

   
    // Display pane for satellite status.  It's current bounds.
	private Rect satBounds;
	   
	// Latest GPS status.  If null, we haven't got one yet.
	private GpsStatus gpsStatus = null;
	   
	// Cached satellite info.  Indexed by the satellite's PRN number,
	// which is in the range 1-NUM_SATS.
	private GpsInfo[] satCache;
	   
	// Number of satellites for which we have info.
	private int numSats;
	
	// The most recent network and GPS locations.
	private Location gpsLocation = null;
	
	// Current geomagnetic data, and the time at which it was fetched.
	// null if it hasn't been got yet.
	private GeomagneticField geomagneticField = null;
	private long geomagneticTime = 0;
	
	// The most recent accelerometer and compass data.
	private float[] accelValues = null;
	private float[] magValues = null;
	

	BufferedWriter writer;
	DateFormat formatter;
	
	long lastChange = 0;
	
    // ******************************************************************** //
    // Local Constants and Classes.
    // ******************************************************************** //

    /**
     * Number of GPS satellites we can handle.  Satellites are numbered 1-32;
     * this is the "PRN number", i.e. a number which identifies the
     * satellite's PRN, which is a 1023-bit number.
     */
    static final int NUM_SATS = 32;
    int lastNumSats = 0;
   
    /**
     * Cached info on a satellite's status.
     */
    static final class GpsInfo {
        GpsInfo(int prn) {
            this.prn = prn;
            this.name = "" + prn;
        }
       
        final int prn;
        final String name;
       
        // Time at which this status was retrieved.  If 0, not valid.
        long time = 0;
       
        float azimuth;
        float elev;
        float snr;
        boolean hasAl;
        boolean hasEph;
        boolean used;
       
        // Time at which this satellite was last used in a fix.
        long usedTime = 0;
       

    }
   


    /**
     * Set up this view.
     *
     * @param       context                 Parent application context.
     * @param   parent          Parent surface.
     * @param   sman            The SensorManager to get data from.
     */
    public GeoLocator(Context context) {
        appContext = context;
        formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.LONG, Locale.getDefault()); 
    }

    public void registerDataStatusChangeListener(DataStatusChangeListener listener) {
		this.statusListener = listener;
    }


    public void startDataLogging() {
		if (writer == null) {
			File file = new File("/sdcard/EV_Speedo.log");
	        try {
	        	if (!file.exists()) {
	        		file.createNewFile();
	        		Log.e(C.TAG, "File Created!");
	        	}
	        	
	        	writer = new BufferedWriter(new FileWriter(file));
	        }
	        catch (IOException e) {
	        	Log.e(C.TAG, e.toString());
	        } 
		}
		
	}
	
	public void stopDataLogging() {
		
       	try {
	       	if (writer != null) {
	       		writer.flush();
	       		writer.close();
	        }
       	} catch (IOException e) {
       		Log.e(C.TAG, e.toString());
       	}

		
	}


        // ******************************************************************** //
        // Data Management.
        // ******************************************************************** //
       
    /**
     * Start this view.  This notifies the view that it should start
     * receiving and displaying data.  The view will also get tick events
     * starting here.
     */
    public void start() {
    	sensorManager = (SensorManager)appContext.getSystemService(Context.SENSOR_SERVICE);

        // Set up the satellite data cache.  For simplicity, we allocate
        // NUM_SATS + 1 so we can index by PRN number.
        satCache = new GpsInfo[NUM_SATS + 1];
        for (int i = 1; i <= NUM_SATS; ++i) {
        	satCache[i] = new GpsInfo(i);
        }
        
        numSats = 0;

        // Get the information providers we need.
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
    }
   
    public void resume() {
   
    	// Get orientation updates.
        registerSensor(Sensor.TYPE_ACCELEROMETER);
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD);	
        
    	if (statusListener != null) {
			statusListener.onDataStatusChange(SourceType.GPS, DataStatus.WAITING);
		}
    	
    	// Register for location updates.
    	if (prov == null) {
    	prov = locationManager.getProvider(LocationManager.GPS_PROVIDER);
    	}
    	if (prov != null) {
    		locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this);
                   
    		// For the GPS, also add a GPS status listener to get
    		// additional satellite and fix info.
    		locationManager.addGpsStatusListener(this);
    		
    		// Prime the pump with the last known location.
    		Location prime = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    		if (prime != null)
    			onLocationChanged(prime);
        }

    	
        
    }
       
    private final void registerSensor(int type) {
    	Sensor sensor = sensorManager.getDefaultSensor(type);
    	if (sensor != null)
    		sensorManager.registerListener(this, sensor,
                                           SensorManager.SENSOR_DELAY_UI);
    }
       
       
    public void pause() {
    	locationManager.removeGpsStatusListener(this);
		locationManager.removeUpdates(this);
    	sensorManager.unregisterListener(this);
    }

    public void destroy() {
    	locationManager = null;
    	sensorManager = null;
        try {
        	lock.lock();
	    	listeners.clear();
        } finally {
        	lock.unlock();
        }
    }
    
        /**
         * Stop this view.  This notifies the view that it should stop
         * receiving and displaying data, and generally stop using
         * resources.
         */
    public void stop() {
    	locationManager = null;
    	sensorManager = null;
    	satCache = null;
    }
       

    // ******************************************************************** //
    // Location Management.
    // ******************************************************************** //

        /**
         * Called when the location has changed.  There are no restrictions
         * on the use of the supplied Location object.
         *
         * @param       loc                        The new location, as a Location object.
         */
        public void onLocationChanged(Location loc) {
        	try {       		
    			lock.lock();
    			if (statusListener != null) {
    				statusListener.onDataStatusChange(SourceType.GPS, DataStatus.RECEIVING);
    			}
    			
    			Log.d(C.TAG, "Location accuracy: " + loc.getAccuracy());
    			float bearing;
    			if (gpsLocation == null) {
    				bearing = -1;
    			} else {
    				bearing = calculateBearing(gpsLocation, loc);
    			}
    			for (SensorDataListener listener : listeners) {
    				for (DataType dt : listener.getDataTypes()) {
	    				switch (dt) {
	    				
	    				case SPEED_KPH:
	    					if (loc.hasSpeed()) {
	    						listener.onData(DataType.SPEED_KPH, Convert.mps_to_kmph(loc.getSpeed()));
	    					}
	    					break;
	    					
	    				case SPEED_MPH:
	    					if (loc.hasSpeed()) {
	    						listener.onData(DataType.SPEED_MPH, Convert.mps_to_mph(loc.getSpeed()));
	    					}
	    					break;
	    		
	    				case LOCATION:
	    					//Log.d(C.TAG, "latitude : " + Location.convert(loc.getLatitude(), Location.FORMAT_SECONDS));
	    					listener.onData(DataType.LOCATION, (float)loc.getLatitude(), (float)loc.getLongitude());
	    					break;
	    	
	    				case ALTITUDE:
	    					if (loc.hasAltitude()) {
	    						listener.onData(DataType.ALTITUDE, (float)loc.getAltitude());
	    					}
	    					
	    				case BEARING:
	    					if (gpsLocation != null) {
	    						listener.onData(DataType.BEARING, bearing);
	    					}
	    					break;
	    				case SATELLITE_ACCURACY:
	    					listener.onData(DataType.SATELLITE_ACCURACY, loc.getAccuracy());
	    					
	    					break;
    		
	    					
	    				}
    				}
    			}
    			
    			if (writer != null) {
    				StringBuilder builder = new StringBuilder();
    				
    				builder.append(formatter.format(new Date(loc.getTime()))).append(",");
    				builder.append(loc.getSpeed()).append(",");
    				builder.append(loc.getLatitude()).append(",");
    				builder.append(loc.getLongitude()).append(",");
    				builder.append(loc.getBearing()).append(",");
    				builder.append(loc.getAccuracy());
    				
    				try {
    					writer.write(builder.toString());
    					writer.newLine();
    				} catch (IOException e) {
    					Log.e(C.TAG, "Error writing log file: " + e.getMessage());
    				}
    			}
    			
    			gpsLocation = loc;
    		} finally {
    			lock.unlock();
    			lastChange = System.currentTimeMillis();
    		}
        }

        
        
        /**
         * Called when the provider status changes.  This method is called
         * when a provider is unable to fetch a location or if the provider
         * has recently become available after a period of unavailability.
         *
         * @param       provider                The name of the location provider
         *                                                      associated with this update.
         * @param       status                  OUT_OF_SERVICE if the provider is out of
         *                                                      service, and this is not expected to
         *                                                      change in the near future;
         *                                                      TEMPORARILY_UNAVAILABLE if the provider
         *                                                      is temporarily unavailable but is expected
         *                                                      to be available shortly; and AVAILABLE if
         *                                                      the provider is currently available.
         * @param       extras                  An optional Bundle which will contain
         *                                                      provider specific status variables.
         *                                                      Common key/value pairs:
         *                                                                satellites - the number of satellites
         *                                                                                         used to derive the fix.
         */
        public void onStatusChanged(String provider, int status, Bundle extras) {
            try {
                Log.i(C.TAG, "Provider status: " + provider + "=" + status);
                if (statusListener == null) return;                
                switch (status) {
                case LocationProvider.OUT_OF_SERVICE:
                	statusListener.onDataStatusChange(SourceType.GPS, DataStatus.STOPPED);                    
                    break;
                case LocationProvider.TEMPORARILY_UNAVAILABLE:
                	statusListener.onDataStatusChange(SourceType.GPS, DataStatus.WAITING);
                    break;
                case LocationProvider.AVAILABLE:
                	
                    break;
                }
            } catch (Exception e) {
            	Log.e(C.TAG, e.getMessage(),e);
            }
        }



    /**
     * Called to report changes in the GPS status.
     *
     * @param   event           Event number describing what has changed.
     */
    public void onGpsStatusChanged(int event) {
    	try {
    		switch (event) {
    		case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
    			if (locationManager == null) return;
    			gpsStatus = locationManager.getGpsStatus(gpsStatus);
    			Iterable<GpsSatellite> sats = gpsStatus.getSatellites();
    			long time = System.currentTimeMillis();
    			for (GpsSatellite sat : sats) {
    				int prn = sat.getPrn();
    				if (prn < 1 || prn > NUM_SATS)
    					continue;

                    GpsInfo ginfo = satCache[prn];
                    ginfo.time = time;
                    ginfo.azimuth = sat.getAzimuth();
                    ginfo.elev = sat.getElevation();
                    ginfo.snr = sat.getSnr();
                    ginfo.hasAl = sat.hasAlmanac();
                    ginfo.hasEph = sat.hasEphemeris();
                    ginfo.used = sat.usedInFix();
                }
    			try {
    				lock.lock();
    			
	    			for (SensorDataListener listener : listeners) {
	    				for (DataType dt : listener.getDataTypes()) {
		    				switch (dt) {
		    		
		    				case SATELLITE_COUNT:
		    					listener.onData(DataType.SATELLITE_COUNT, numSats);
		    					break;
		    				}
	    				}
	    			}
    			} finally {
    				lock.unlock();
    			}

              
    			numSats = 0;
    			for (int prn = 1; prn <= NUM_SATS; ++prn) {
    				GpsInfo ginfo = satCache[prn];
    				if (time - ginfo.time > DATA_CACHE_TIME) {
    					ginfo.time = 0;
    					ginfo.usedTime = 0;
                    } else {
                    	if (ginfo.used)
                    		ginfo.usedTime = time;
                        else if (time - ginfo.usedTime <= DATA_CACHE_TIME)
                        	ginfo.used = true;
                        else
                        	ginfo.usedTime = 0;
                        
                    	
                    	++numSats;
                    }
                }
    			
    			
    			if (lastNumSats != numSats) {
    				Log.d(C.TAG, "Number Satellites: " + numSats);
    				lastNumSats = numSats;
	    			if (numSats < 5) {
	    				statusListener.onDataStatusChange(SourceType.GPS, DataStatus.WAITING);
	    				for (SensorDataListener listener : listeners) {
	        				for (DataType dt : listener.getDataTypes()) {
	    	    				switch (dt) {
	    	    				case SATELLITE_ACCURACY:
	    	    					listener.onData(DataType.SATELLITE_ACCURACY, -1);
	    	    				}
	        				}
	    				}
	    			}
    			}
    			break;
    			
            case GpsStatus.GPS_EVENT_STARTED:
            	Log.d(C.TAG, ": GPS Event Started");
            	statusListener.onDataStatusChange(SourceType.GPS, DataStatus.WAITING);
            	for (SensorDataListener listener : listeners) {
    				for (DataType dt : listener.getDataTypes()) {
	    				switch (dt) {
	    				case SATELLITE_ACCURACY:
	    					listener.onData(DataType.SATELLITE_ACCURACY, -1);
	    				}
    				}
				}
                break;
            case GpsStatus.GPS_EVENT_STOPPED:
            	Log.d(C.TAG, ": GPS Event Stopped");
            	statusListener.onDataStatusChange(SourceType.GPS, DataStatus.STOPPED);
            	for (SensorDataListener listener : listeners) {
    				for (DataType dt : listener.getDataTypes()) {
	    				switch (dt) {
	    				case SATELLITE_ACCURACY:
	    					listener.onData(DataType.SATELLITE_ACCURACY, -2);
	    				}
    				}
				}
            	break;
            case GpsStatus.GPS_EVENT_FIRST_FIX:
            	Log.d(C.TAG, ": GPS Event First Fix");
            	statusListener.onDataStatusChange(SourceType.GPS, DataStatus.RECEIVING);
                break;
            
            }
        } catch (Exception e) {
        	Log.e(C.TAG, e.getMessage(),e);
        }
    }


    // ******************************************************************** //
    // Geomagnetic Data Management.
    // ******************************************************************** //

    /**
     * Check the geomagnetic field, if the information we have isn't
     * up to date.
     */
    private void checkGeomag() {
        // See if we have valid data.
        long now = System.currentTimeMillis();
        if (geomagneticField != null && now - geomagneticTime < GEOMAG_CACHE_TIME)
            return;
       
        // Get our best location.  If we don't have one, can't do nothing.
        final Location loc = gpsLocation;
        if (loc == null)
            return;

        // Get the geomag data.
        geomagneticField = new GeomagneticField((float) loc.getLatitude(),
                                                (float) loc.getLongitude(),
                                                (float) loc.getAltitude(), now);    
        geomagneticTime = now;
    }
 

    // ******************************************************************** //
    // Sensor Management.
    // ******************************************************************** //

    /**
     * Called when the accuracy of a sensor has changed.
     *
     * @param   sensor          The sensor being monitored.
     * @param   accuracy        The new accuracy of this sensor.
     */
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Don't need anything here.
    }


    /**
     * Called when sensor values have changed.
     *
     * @param   event           The sensor event.
     */
    public void onSensorChanged(SensorEvent event) {
        boolean locked = false;
    	try {
        	
            final float[] values = event.values;
            if (values.length < 3)
                return;

            int type = event.sensor.getType();
            if (type == Sensor.TYPE_ACCELEROMETER) {
                if (accelValues == null)
                    accelValues = new float[3];
                accelValues[0] = values[0];
                accelValues[1] = values[1];
                accelValues[2] = values[2];
            } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
                if (magValues == null)
                    magValues = new float[3];
                magValues[0] = values[0];
                magValues[1] = values[1];
                magValues[2] = values[2];
            }
            
            if (accelValues == null || magValues == null)
                return;
            
            lock.lock();
            locked = true;
            for (SensorDataListener listener : listeners) {
				for (DataType dt : listener.getDataTypes()) {
    				switch (dt) {
    				
    				case AZIMUTH:
    					checkGeomag();
    					if (geomagneticField != null) {
    			            // Get the device rotation matrix.
    			            float[] rotate = new float[9];
    			            boolean ok = SensorManager.getRotationMatrix(rotate, null, accelValues, magValues);
    			            if (!ok)
    			                break;
    						// Compute the device's orientation based on the rotation matrix.
    			            final float[] orient = new float[3];
    			            SensorManager.getOrientation(rotate, orient);
    			            
    			            // Get the azimuth of device Y from magnetic north.  Compensate for
    			            // magnetic declination.
    			            final float azimuth = (float) Math.toDegrees(orient[0]);
    			            final float dec = geomagneticField.getDeclination();
    			            //Log.d(C.TAG, "Azimuth = " + avgAzimuth.average(azimuth + dec - 90));
    			            listener.onData(DataType.AZIMUTH, avgAzimuth.average(azimuth + dec + 90));
    					}
    					break;
    				case LATERALG:
    					listener.onData(DataType.LATERALG, avgAccG.average(accelValues[1]));
    					break;
    				case ACCELERATIONG:
    					listener.onData(DataType.ACCELERATIONG, avgLatG.average(accelValues[2]));
    					break;
    				}
				}
            }
            
        } catch (Exception e) {
        	Log.e(C.TAG, e.getMessage(),e);
        } finally {
        	if (locked) lock.unlock();
        }
    }

    private DigitalAverage avgAzimuth = new DigitalAverage();

    private DigitalAverage avgLatG = new DigitalAverage();
    private DigitalAverage avgAccG = new DigitalAverage();

	public void onProviderDisabled(String arg0) {
		
	}

	public void onProviderEnabled(String arg0) {
		
	}
	
	/**
     * Calculates the bearing of the two Locations supplied and returns the
     * Angle in the following (GPS-likely) manner: <br />
     * <code>N:0�, E:90�, S:180�, W:270�</code>
     */
    private float calculateBearing(Location before, Location after) {
         Point pBefore = location2Point(before);
         Point pAfter = location2Point(after);

         float res = -(float) (Math.atan2(pAfter.y - pBefore.y, pAfter.x
                   - pBefore.x) * 180 / Math.PI) + 90.0f;
         Log.d(C.TAG, "Bearing: " + res);
         if (res < 0)
              return res + 360.0f;
         else
              return res;
    }
    
    /** Converts an <code>android.location.Location</code> to an <code>android.graphics.Point</code>. */
    public static Point location2Point(Location aLocation){
         return new Point((int) (aLocation.getLongitude() * 1E6),
                             (int) (aLocation.getLatitude() * 1E6));
    }
    

	public void registerSensorDataListener(SensorDataListener listener) {
		try {
			lock.lock();
			listeners.add(listener);
		} finally {
			lock.unlock();
		}
	}
	
	public void unregisterSensorDataListener(SensorDataListener listener) {
		try {
			lock.lock();
			listeners.remove(listener);
		} finally {
			lock.unlock();
		}
	}
	
	private class DigitalAverage {

        final int history_len = 4;
        double[] mLocHistory = new double[history_len];
        int mLocPos = 0;

       
        float average(double d) {
            float avg = 0;

            mLocHistory[mLocPos] = d;

            mLocPos++;
            if (mLocPos > mLocHistory.length - 1) {
                mLocPos = 0;
            }
            for (double h : mLocHistory) {
                avg += h;
            }
            avg /= mLocHistory.length;

            return avg;
        }
        /*
        int range() {
        	double min = mLocHistory[0];
        	double max = mLocHistory[0];
            for (double h : mLocHistory) {
                if (h <min) min = h;
            	if (h > max) max = h;
            }
            
            return (int)(max-min);
        }*/
    }
}
