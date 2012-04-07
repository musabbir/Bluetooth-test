package com.androidTestProject;

import java.util.List;

import com.fezDominoCommunication.Bluetooth;
import com.fezDominoCommunication.MessageResponse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class AndroidTestProjectActivity extends Activity {
    
	//address of the device. Update for your device in code, or change in the app through the menu
	public static final String DEVICE_ADDRESS = "00:0A:3A:2B:C7:39";
	public String deviceAddress = DEVICE_ADDRESS;
	
	//Tag for logging
	public final String TAG = "Main";
	
	//Main textView where all the info is put
	private TextView textField;

	private TextView speedTextView;
	
	//Buttons that are across the top of the app
	private Button sendButton;
	private Button clearButton;
	private Button reconnectButton;
	private Button listButton;
	private Button stateButton;
	
	//Whether or not we should be receiving state updates
	private boolean sendingState = false;
	
	//Bluetooth class that does all the send/receive/connect stuff
	private Bluetooth bluetooth;
	
	/*fired when the application is opened*/
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	//generic android stuff
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        //getting references to the buttons and textView onscreen
        sendButton = (Button) findViewById(R.id.button1);
        textField = (TextView) findViewById(R.id.editText1);
        clearButton = (Button) findViewById(R.id.button2);
        reconnectButton = (Button) findViewById(R.id.button3);
        listButton = (Button) findViewById(R.id.button4);
        stateButton = (Button) findViewById(R.id.button5);
        speedTextView = (TextView) findViewById(R.id.textView1);
        
        //create a bluetooth communication object
        try {
        	this.bluetooth = new Bluetooth(this);
        } catch (Exception e) {
        	addLine(e.toString());
        }
        
        
        //set events that are fired when buttons get clicked
        sendButton.setOnClickListener(new View.OnClickListener() {
			
			public void onClick(View v) {
				
				Long start = System.currentTimeMillis();
				MessageResponse response = bluetooth.sendMessage("abcdefgh");
				Long elapsed = System.currentTimeMillis() - start;
				
				if(response.isSuccess())
				{
					addLine("Success, time: " + Long.toString(elapsed) + " ms - " + response.getResponse());
				}
				else
				{
					addLine("FAILURE, code " + Integer.toString(response.getErrorCode()) + " msg: " + response.getErrorMsg());
				}
			}
		});
		
        clearButton.setOnClickListener(new View.OnClickListener(){

			public void onClick(View arg0) {
				textField.setText("");
			}
        	
        });
        
        reconnectButton.setOnClickListener(new View.OnClickListener() {
			
			public void onClick(View v) {
				bluetooth.Disconnect();
				if(bluetooth.Connect(deviceAddress))
					addLine("Reconnected");
				else
					addLine("Reconnect failed");
			}
		});
        
        listButton.setOnClickListener(new View.OnClickListener(){

			public void onClick(View v) {
				List<String> devices = bluetooth.getDeviceAddresses();
				for(String device : devices)
				{
					addLine(device);
				}
			}
        
        });

        stateButton.setOnClickListener(new View.OnClickListener() {
			
			public void onClick(View arg0) {
				Long start = System.currentTimeMillis();
				if(sendingState)
				{
					stateButton.setText("Start");
					bluetooth.stopStateUpdates();
				}
				else
				{
					stateButton.setText("Stop");
					bluetooth.beginStateUpdates();
				}
				sendingState = !sendingState;
				Long elapsed = System.currentTimeMillis() - start;
				addLine("Took " + Long.toString(elapsed) + " ms to start/stop state updates");
			}
		});
    } 
    
    /*adds a line to the textfield that is most of the UI*/
    public void addLine(String s)
    {
    	textField.setText(textField.getText().toString() + "\n" + s);
    }
    
    /*fired when the app goes into the background/closes*/
    @Override
    protected void onStop()
    {
    	super.onStop();
    	bluetooth.Disconnect();
    }
    
    /*this creates the menu that shows up when you hit the menu key*/
    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }
    
    /*fired when you select an item on the menu*/
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
        case R.id.perf_msg_fast:
        	performance_test_msg_fast();
            return true;
        case R.id.set_address:
        	setAddress();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }
    
    /* a quick test of message sending performance, sending continuously*/
    public void performance_test_msg_fast(){
    	if(!bluetooth.isConnected())
    		bluetooth.Connect(deviceAddress);
    	
    	String msg = "abc";
    	
    	int count = 20;
    	Long start1Ms = System.currentTimeMillis();
    	for(int i=0;i<count;i++)
    	{
    		bluetooth.sendMessage(msg);
    	}
    	
    	Long end1Ms = System.currentTimeMillis();
    	Long time1 = end1Ms - start1Ms;
    	
    	try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	msg = "Nabcdefghijklmnopqrstuvwxzy01234567890123456789012";
    	
    	Long start2Ms = System.currentTimeMillis();
    	for(int i=0;i<count;i++)
    	{
    		bluetooth.sendMessage(msg);
    	}
    	
    	Long end2Ms = System.currentTimeMillis();
    	Long time2 = end2Ms - start2Ms;
    	
    	//show results
    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	builder.setMessage("sending 3 byte msg: " + Long.toString(time1/count) + " ms/msg.\nSending 50 byte msg: " + Long.toString(time2/count) + " ms/msg.");
    	builder.setNeutralButton("Ok", new DialogInterface.OnClickListener() {
			
			public void onClick(DialogInterface dialog, int which) {
				dialog.cancel();
			}
		});
    	AlertDialog alert = builder.create();
    	alert.show();
    }
    
    /*set the address that bluetooth connects to*/
    public void setAddress(){
    	Toast toast = Toast.makeText(this, "Not yet implemented", Toast.LENGTH_SHORT);
    	toast.show();
    }
    
    private Handler speedUpdateHandler = new Handler(){
    	@Override
    	public void handleMessage(Message msg)
    	{
    		int fps = (Integer)msg.obj;
    		updateSpeed(fps);
    	}
    };
    
    public void updateSpeed(int fps)
    {
    	this.speedTextView.setText(Integer.toString(fps));
    }

	public Handler getSpeedUpdateHandler() {
		return speedUpdateHandler;
	}

	public void setSpeedUpdateHandler(Handler speedUpdateHandler) {
		this.speedUpdateHandler = speedUpdateHandler;
	}
}