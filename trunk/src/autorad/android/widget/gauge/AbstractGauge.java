package autorad.android.widget.gauge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.FrameLayout;
import autorad.android.C;
import autorad.android.DashDisplay;
import autorad.android.sensor.DataType;
import autorad.android.sensor.SensorDataListener;
import autorad.android.widget.AbsoluteLayout;

public abstract class AbstractGauge extends FrameLayout implements SensorDataListener {

	DashDisplay ctx;
	GaugeType gaugeType;
	DataType[] dataTypes;
	GaugeSettings gaugeSettings;
	GaugeDetails details;
	int size;
	
	public AbstractGauge(DashDisplay ctx) {
		super(ctx);
	}
	
	public abstract void calibrate();
	
	public abstract String getToastString();
	
	public abstract void cleanup();
	
	public abstract void applySettings();
	
	public AbsoluteLayout.LayoutParams getLayout() {
		return new AbsoluteLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gaugeSettings.getPosX() , gaugeSettings.getPosY());
	}

	public DataType[] getDataTypes() {
		return dataTypes;
	}
	
	public int getSize() {
		return this.size;
	}
	
	public GaugeSettings getSettings() {
		return gaugeSettings;
	}
	
	public void save() {
		String jsonSettings = gaugeSettings.getSettingsAsJSONString();
		if (C.D) Log.d(C.TAG, "Saving gauge - " + gaugeType.name() + ": " + jsonSettings); 
		SharedPreferences gPrefs = ctx.getSharedPreferences("G", Context.MODE_PRIVATE);
	    SharedPreferences.Editor prefsEditor = gPrefs.edit();
	    prefsEditor.putString(gaugeType.name(), jsonSettings);
	    prefsEditor.commit();
	}

	
	
}
