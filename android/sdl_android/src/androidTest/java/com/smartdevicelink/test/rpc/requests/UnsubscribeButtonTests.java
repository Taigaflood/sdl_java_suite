package com.smartdevicelink.test.rpc.requests;

import com.smartdevicelink.marshal.JsonRPCMarshaller;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.rpc.UnsubscribeButton;
import com.smartdevicelink.proxy.rpc.enums.ButtonName;
import com.smartdevicelink.test.BaseRpcTests;
import com.smartdevicelink.test.JsonUtils;
import com.smartdevicelink.test.TestValues;
import com.smartdevicelink.test.json.rpc.JsonFileReader;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Hashtable;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.fail;
import static android.support.test.InstrumentationRegistry.getContext;

/**
 * This is a unit test class for the SmartDeviceLink library project class : 
 * {@link com.smartdevicelink.rpc.UnsubscribeButton}
 */
public class UnsubscribeButtonTests extends BaseRpcTests {
	
	@Override
	protected RPCMessage createMessage() {
		UnsubscribeButton msg = new UnsubscribeButton();

		msg.setButtonName(TestValues.GENERAL_BUTTONNAME);

		return msg;
	}

	@Override
	protected String getMessageType() {
		return RPCMessage.KEY_REQUEST;
	}

	@Override
	protected String getCommandType() {
		return FunctionID.UNSUBSCRIBE_BUTTON.toString();
	}

	@Override
	protected JSONObject getExpectedParameters(int sdlVersion) {
		JSONObject result = new JSONObject();

		try {
			result.put(UnsubscribeButton.KEY_BUTTON_NAME, TestValues.GENERAL_BUTTONNAME);
			
		} catch (JSONException e) {
			fail(TestValues.JSON_FAIL);
		}

		return result;
	}

	/**
	 * Tests the expected values of the RPC message.
	 */
	@Test
    public void testRpcValues () { 
    	// Test Values
    	ButtonName testButtonName = ( (UnsubscribeButton) msg ).getButtonName();
		
    	// Valid Tests
		assertEquals(TestValues.MATCH, TestValues.GENERAL_BUTTONNAME, testButtonName);
		
		// Invalid/Null Tests
		UnsubscribeButton msg = new UnsubscribeButton();
		assertNotNull(TestValues.NOT_NULL, msg);
		testNullBase(msg);

		assertNull(TestValues.NULL, msg.getButtonName());
    }
	
	/**
     * Tests a valid JSON construction of this RPC message.
     */
	@Test
    public void testJsonConstructor () {
    	JSONObject commandJson = JsonFileReader.readId(getContext(), getCommandType(), getMessageType());
    	assertNotNull(TestValues.NOT_NULL, commandJson);
    	
		try {
			Hashtable<String, Object> hash = JsonRPCMarshaller.deserializeJSONObject(commandJson);
			UnsubscribeButton cmd = new UnsubscribeButton(hash);
			
			JSONObject body = JsonUtils.readJsonObjectFromJsonObject(commandJson, getMessageType());
			assertNotNull(TestValues.NOT_NULL, body);
			
			// Test everything in the json body.
			assertEquals(TestValues.MATCH, JsonUtils.readStringFromJsonObject(body, RPCMessage.KEY_FUNCTION_NAME), cmd.getFunctionName());
			assertEquals(TestValues.MATCH, JsonUtils.readIntegerFromJsonObject(body, RPCMessage.KEY_CORRELATION_ID), cmd.getCorrelationID());

			JSONObject parameters = JsonUtils.readJsonObjectFromJsonObject(body, RPCMessage.KEY_PARAMETERS);
			assertEquals(TestValues.MATCH, JsonUtils.readStringFromJsonObject(parameters, UnsubscribeButton.KEY_BUTTON_NAME), cmd.getButtonName().toString());
		} 
		catch (JSONException e) {
			e.printStackTrace();
		}    	
    }	
}