package com.fezDominoCommunication;
/*
 * This class is returned by Bluetooth.send(), to contain both the response from the 
 * microcontroller, and any error message and error code if it doesn't work
 */
public class MessageResponse {
	int errorCode;
	String response;
	String error;
	
	public static int ERROR_UNKNOWN = -1;
	public static int ERROR_SUCCESS = 0;
	public static int ERROR_NOT_CONNECTED = 1;
	public static int ERROR_SEND_FAILURE = 2;
	public static int ERROR_READ_FAILURE = 3;
	public static int ERROR_TIMEOUT = 4;
	
	public MessageResponse(int errorCode, String response, String error)
	{
		this.errorCode = errorCode;
		this.response = response;
		this.error = error;
	}
	
	public static MessageResponse newError(int code, String message)
	{
		return new MessageResponse(code, null, message);
	}
	
	public static MessageResponse newSuccess(String response)
	{
		return new MessageResponse(ERROR_SUCCESS, response, null);
	}
	
	public boolean isSuccess()
	{
		return this.errorCode == ERROR_SUCCESS;
	}
	
	public int getErrorCode()
	{
		return this.errorCode;
	}
	
	public String getResponse()
	{
		return this.response;
	}
	
	public String getErrorMsg()
	{
		return this.error;
	}
}
