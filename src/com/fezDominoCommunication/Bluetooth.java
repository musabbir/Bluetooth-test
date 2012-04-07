package com.fezDominoCommunication;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.androidTestProject.AndroidTestProjectActivity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Message;
import android.util.Log;

public class Bluetooth {
	
	//requires to start the bluetooth connection
	private final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	
	//message formatting - this should probably change to be more efficient
	private static String PARTS_SPLIT = ":";
	private static String MESSAGE_END = "\n";
	
	//time to wait before giving up waiting for a response from sending the microcontroller a message
	private static long RESPONSE_TIMEOUT_MS = 10000;	
	
	//commands that the micro controller knows.
	//These should definitely be shorter, but for now, oh well
	private String START_STATE_UPDATES_MSG = "START_STATE";
	private String STOP_STATE_UPDATES_MSG = "STOP_STATE";
	
	private static int BAUD_RATE = 57600; //9600
	
	//class name for logging
	String TAG = "Bluetooth";
	
	//current state - this probably shouldn't be a string but for now it is
	String current_state = null;
	
	//counters so we can get an idea of the speed of state updates
	Long startStateUpdateMs = -1L;
	int updateCount = 0;
	
	//response types - each message that comes in should have one of these at the start
	//that way the read thread knows what to do with the message
	public static enum RESPONSE_TYPE{STATE, RESPONSE};
	private static String STATE_KEY = "S";
	private static String RESPONSE_KEY = "R";
	
	//bluetooth communication stuff
	BluetoothAdapter bluetoothAdapter;
	BluetoothSocket socket;
	
	//bluetooth input and output stream that we can read and write to/from
	InputStream inStream;
	OutputStream outStream;
	
	//Thread that constantly reads from the bluetooth input stream
	//when it gets a complete message, it gets added to the list of messages
	ReadThread readThread;
	
	//List of messages that the ReadThread adds to everytime it gets a message
	//These are read when we're looking for a response back
	private List<ResponseMessage> messages; 
	//lock this whenever you wanna modify the list of messages
	//makes sure nothing funny happens between the ReadThread adding to them, and other threads removing items
	private Lock messagesLock;
	
	private AndroidTestProjectActivity parent;

	/*
	 * Create the bluetooth object
	 * Does not connect, but will turn on the bluetooth hardware 
	 * (user gets prompted)
	 */
	public Bluetooth(AndroidTestProjectActivity context) throws Exception
	{
		bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if(bluetoothAdapter == null)
			throw new Exception("Bluetooth not supported on this device");
		
		if(!bluetoothAdapter.isEnabled())
		{
			Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			context.startActivity(enableBtIntent);
		}
		
		this.messagesLock = new ReentrantLock();
		this.parent = context;
		
	}
	
	/*
	 * Connect to the device at the provided address
	 * Returns whether this was successful, prints stuff to log
	 * if it failed
	 */
	public boolean Connect(String address)
	{
		if(this.socket != null)
		{
			Log.d(TAG, "Aborting connect - socket is not null");
			return false;
		}
		
		if(!BluetoothAdapter.checkBluetoothAddress(address))
		{
			Log.d(TAG, "Aborting connect - checkBluetoothAddress() failed");
			return false;
		}
		
		BluetoothDevice device = this.bluetoothAdapter.getRemoteDevice(address);
		
		if(device == null)
		{
			Log.d(TAG, "Connect failed - device is null");
			return false;
		}
		
		try
		{
			this.socket = device.createRfcommSocketToServiceRecord(this.SPP_UUID);
		} catch(IOException e) {
			Log.d(TAG, "failed to createRfcommSocketToServiceRecord(): " + e.toString());
			return false;
		}
			
		try
		{
			this.socket.connect();
		} catch (IOException e){
			Log.d(TAG, "failed to connect to socket: " + e.toString());
			try
			{
				this.socket.close();
			} catch (Exception e2){}
			
			return false;
		}
		
		try
		{
			this.inStream = this.socket.getInputStream();
		} catch (IOException e)
		{
			Log.d(TAG, "Failed to get input stream from socket: " + e.toString());
			try
			{
				this.socket.close();
			} catch (Exception e2){}
			
			return false;
		}
		
		try
		{
			this.outStream = this.socket.getOutputStream();
		} catch (IOException e)
		{
			Log.d(TAG, "Failed to get output stream from socket: " + e.toString());
			try
			{
				this.socket.close();
			} catch (Exception e2){}
			
			return false;
		}
		
		this.messages = new ArrayList<ResponseMessage>();
		this.readThread = new ReadThread(this.inStream);
		this.readThread.start();
		
		Log.d(TAG, "Connect succeeded");
		
		return true;
	}
	
	/*
	 * Tell the bluetooth connection to stop, make socket, inStream and outStream all null too
	 */
	public boolean Disconnect()
	{
		if(this.readThread != null)
		{
			this.readThread.stopReading();
			try
			{
				this.readThread.join(1000);
			}
			catch(InterruptedException e)
			{
				Log.d(TAG, "Joining readThread threw exception: " + e.toString());
			}
		}
		
		if(socket == null)
		{
			return false;
		}
		
		try
		{
			socket.close();
		} catch(IOException e)
		{
			Log.d(TAG, "Caught exception trying to close socket: " + e.toString());
			return false;
		}
		

		this.inStream = null;
		this.outStream = null;
		this.socket = null;
		
		Log.d(TAG, "Disconnect succeeded");
		
		return true;
	}
	
	/*
	 * Theres probably a better way to check than this
	 */
	public boolean isConnected()
	{
		return socket != null;
	}
	
	/*
	 * Send a message to the controller, and receive a response
	 * Return value is the message received from the device
	 * A unique id is appended to the message being sent, and is expected in message returned
	 * This way we can know that the response received was from this particular message (if many messages are sent very quickly)
	 */
	public MessageResponse sendMessage(String msg)
	{
		//make sure the message ends with the special message ending character
		if(!msg.endsWith(MESSAGE_END))
			msg += MESSAGE_END;
		
		//make sure we have a stream to read and write from
		if(this.inStream == null || this.outStream == null)
			return MessageResponse.newError(MessageResponse.ERROR_NOT_CONNECTED, "input or output stream was null");
		
		//get a unique id for the message
		//this way we can be sure the response we receive is from the request that is sent
		String messageID = getUniqueID();
		Log.d(TAG, "messageId is: " + messageID);
		
		Long startSend = System.currentTimeMillis();
		
		//get the complete message, then in bytes
		String toSend = messageID + PARTS_SPLIT + msg;
		
		//toSend = "NNNNN";
		//Log.d(TAG, "WARNING: SENDING A GARBAGE MESSAGE!!"); 
		
		byte[] outBuffer = toSend.getBytes();
		
		Log.d(TAG, "length to send: " + Integer.toString(toSend.length()) + "  msg: " + toSend);
		
		//write the msg to the output stream.  Needs locking?
		try
		{
			this.outStream.write(outBuffer);
		} catch (IOException e)
		{
			return MessageResponse.newError(MessageResponse.ERROR_SEND_FAILURE, e.toString());
		}
		
		/*
		 * Now that the message is sent, the microcontroller is expected to respond with some kind of acknoledgement
		 * The ReadThread will receive this message, and insert it into "message", a list of ResponseMessage
		 * So we keep looking through the list of ResponseMessages until one exists with the uniqueId that we 
		 * previously sent out.
		 * If this doesn't occur within the timeout, we just return an error MessageResponse to indicate this
		 */
		
		Long sendElapsed = System.currentTimeMillis() - startSend;
		Log.d(TAG, "Sending message out took " + Long.toString(sendElapsed) + " ms");
		
		long startWaitTime = System.currentTimeMillis();
		ResponseMessage properMessage = null;
		while((System.currentTimeMillis() - startWaitTime) < RESPONSE_TIMEOUT_MS)
		{
			//lock messages, cause you can't iterate over a list that is modified during iteration
			messagesLock.lock();
			for(ResponseMessage message : this.messages){
				properMessage = message;
				break;
			}
			
			if(properMessage != null)
			{
				//list also needs to be locked here, so it doesn't remove the wrong message or something weird like that
				this.messages.remove(properMessage);
				messagesLock.unlock();
				
				Long receiveElapsed = System.currentTimeMillis() - startWaitTime;
				Log.d(TAG, "Receiving a response to msg took " + Long.toString(receiveElapsed) + " ms");
				
				//we has success!
				return MessageResponse.newSuccess(properMessage.msg);
			}
			
			messagesLock.unlock();
		}

		//if we got out of the while loop, we timed out
		return MessageResponse.newError(MessageResponse.ERROR_TIMEOUT, "");
	}
	
	/*
	 * tell the microcontroller to start sending state updates
	 * We just send a message that is agreed upon between this and the microcontroller
	 */
	public boolean beginStateUpdates()
	{
		updateCount = 0;
		//startStateUpdateMs = System.currentTimeMillis();
		startStateUpdateMs = -1L;
		
		MessageResponse response = this.sendMessage(START_STATE_UPDATES_MSG);
		
		if(response.isSuccess())
			return true;
		
		Log.d(TAG, "Sending begin state updates message error: " + response.error);
		return false;
	}
	
	/*
	 * tell the microcontroller to stop sending state updates	 
	 * We just send a message that is agreed upon between this and the microcontroller
	 */
	public boolean stopStateUpdates()
	{
		MessageResponse response = this.sendMessage(STOP_STATE_UPDATES_MSG);
		
		if(response.isSuccess())
			return true;
		
		Log.d(TAG, "Sending stop state updates message error: " + response.error);
		return false;
	}
	
	/*
	creates a unique id string for each message to be sent.
	right now it just returns the current time in ms, however this
	is pretty awful, since the messages that are likely to be confused
	are likely to have been sent at almost exactly the same time
	*/
	private String getUniqueID()
	{
		return Long.toString(System.currentTimeMillis());
	}
	
	/*returns a list of device names and addresses that the device has been connected to
	 * Probably not all that useful
	 */
	public List<String> getDeviceAddresses()
	{
		ArrayList<String> result = new ArrayList<String>();
		
		Set<BluetoothDevice> devices = this.bluetoothAdapter.getBondedDevices();
		for(BluetoothDevice device : devices)
		{
			result.add(device.getName() + " - " + device.getAddress());
		}
		
		return result;
	}
	
	/*
	 * This is created when the readThread receives a full string message from the microcontroller
	 * Contains the Id that the original request was sent with so we can be sure to match request and responses
	 * also contains the string that was received, and the type of message (currently just state updates and responses)
	 */
	private class ResponseMessage
	{
		String ID;
		String msg;
		RESPONSE_TYPE responseType;
		
		String TAG = "ResponseMessage";
		
		public ResponseMessage(String s)
		{
			String[] parts = s.split(PARTS_SPLIT);
			if(parts[0].equals(STATE_KEY))
				this.responseType = RESPONSE_TYPE.STATE;
			else if(parts[0].equals(RESPONSE_KEY))
				this.responseType = RESPONSE_TYPE.RESPONSE;
			else
				Log.d(TAG, "Got incorrect response type of '" + parts[0] + "'");
			
			this.ID = parts[1];
			
			this.msg = "";
			for(int i=2;i<parts.length;i++)
				this.msg = parts[i] + PARTS_SPLIT;
			
			if(this.msg.length() > 0)
				this.msg = this.msg.substring(0, this.msg.length() - 1);
			
			//Log.d(TAG, "Made message with id: " + ID + " and message: " + msg);
		}
	}
	
	/*
	 * This is a thread that runs always
	 * It constantly reads from the bluetooth connection, and whenever it gets a full msg, it will add it to the list of messages
	 */
	private class ReadThread extends Thread
	{
		//amount to read at a time
		private int READ_BUFFER_SIZE = 1024;
		
		//copy of stream from the bluetooth socket
		private InputStream inStream;
		
		//used to get this thread to stop from another thread without just killing it, cause thats not nice
		private boolean continueReading = true;
		
		//Name for logging
		private String TAG = "ReadThread";
		
		public ReadThread(InputStream inStream)
		{
			this.inStream = inStream;
		}
		
		public void stopReading()
		{
			this.continueReading = false;
		}
		
		/*
		 * This gets called when you call thread.start()
		 */
		public void run()
		{
			Log.d(TAG, "Started to run ReadThread");
			
			//create a buffer to receive stuff from
			byte[] buffer = new byte[READ_BUFFER_SIZE];
			
			//bytes read from the stream
			int bytes;
			
			//a complete message.  Keep this so that we have the full string received if it's longer than a single buffer length, or the messgae is broken up for whatever other reason
			String completeMsg = "";
			
			//continueReading gets set to false when we wanna stop the thread
			while(this.continueReading)
			{
				//get some bytes
				try
				{
					bytes = this.inStream.read(buffer);
				} catch (IOException e){
					Log.d(TAG, "Caught IOException with message: " + e.toString());
					break;
				}
				if(bytes > 0)
				{
					//for(int i=0;i<bytes;i++)
					//{
					//	Log.d(TAG, "Byte: " + Byte.toString(buffer[i]));
					//}
					
					//get the string that was received with this call to read, add to the previous message from last time
					String partial = new String(buffer).substring(0, bytes);
					
					//Log.d(TAG, "read " + Integer.toString(bytes) + " bytes - string is: " + partial);

					completeMsg += partial;
					
					//check if we have a complete message yet
					if(completeMsg.contains(MESSAGE_END))
					{
						String[] parts = completeMsg.split(MESSAGE_END);

						//this part is kinda bad, but right now we split on MESSAGE_END, the first one is guaranteed to be a complete message so we make a ResponseMessage from that
						//The rest is just joined back together by the MESSAGE_END string, in hopes that next time we run through here it'll be split and get some ResponseMessage objects
						completeMsg = "";
						for(int i=1;i<parts.length;i++)
						{
							completeMsg += parts[i] + MESSAGE_END;
						}	
						
						if(completeMsg.length() > 0)
							completeMsg = completeMsg.substring(0, completeMsg.length() -1);
						
						ResponseMessage rm;
						try
						{
						rm = new ResponseMessage(parts[0]);
						} catch(Exception e)
						{
							Log.d(TAG, "Caught exception trying to create a ResponseMessage", e);
							continue;
						}
						
						//if it's a response message, another thread is waiting for this
						if(rm.responseType == RESPONSE_TYPE.RESPONSE)
						{
							//add the ResponseMessage to the list of messages, making sure to lock before and unlock after to synchronize between threads
							messagesLock.lock();
							messages.add(rm);
							messagesLock.unlock();
						}
						else if(rm.responseType == RESPONSE_TYPE.STATE)
						{
							//if it's a state update, we just want to set the current state to this, and then do some timing work to figure out how fast updates are coming in
							
							if(startStateUpdateMs < 0)
							{
								startStateUpdateMs = System.currentTimeMillis();
							} 
							else
							{
								updateCount+=1;
								if(updateCount % 10 == 0)
								{
									if(startStateUpdateMs != null){
										int fps = (int)(1000 * updateCount / (System.currentTimeMillis() - startStateUpdateMs));
										Log.d(TAG, "Current updates per second: " + Integer.toString(fps));

										//update the UI about the new receive rate
										Message msg = new Message();
										msg.obj = fps;
										parent.getSpeedUpdateHandler().sendMessage(msg);
									}
									else
									{
										Log.d(TAG, "startStateUpdateMs is null for some reason");
									}
								}
								//Log.d(TAG, "received state");
								current_state = rm.msg;
							}
						}
						else
						{
							Log.d(TAG, "Unkown response type on message");
						}
						
					}
				}
			}
			
			Log.d(TAG, "Exited Reading loop");
		}
	}
}
