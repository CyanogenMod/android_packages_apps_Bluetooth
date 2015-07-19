/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 *      copyright notice, this list of conditions and the following
 *      disclaimer in the documentation and/or other materials provided
 *      with the distribution.
 *    * Neither the name of The Linux Foundation nor the names of its
 *      contributors may be used to endorse or promote products derived
 *      from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth;
import java.util.UUID;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import java.io.IOException;
import android.bluetooth.SdpOppOpsRecord;
public class OolConnManager {
	private static final String TAG="OolConnManager";
	static int channel = 0;
	static boolean sdpDone = false;
	static String mAddress;
public static BluetoothSocket CreateL2capConnection(BluetoothDevice remBtDev,UUID uuid ) {
Log.d(TAG,"Inside Create L2capConnection");

	
	 try {
	 	Log.d(TAG,"Calling createL2capSocket");
     return remBtDev.createL2capSocket(channel);
	 	}
	 catch (IOException e) {
Log.e(TAG, "BtSocket Connect error " + e.getMessage());

	 }
	 return null;
	}
public static void setSdpInitiatedAddress(BluetoothDevice remBtDev) {
	
	mAddress = remBtDev.getAddress();
	Log.d(TAG,"prp setSdpInitiatedAddress "+ mAddress);
}

public static int getL2cPSM(BluetoothDevice remBtDev) {
Log.d(TAG,"Inside getL2cPSM");
int waitCount = 0;
while(!sdpDone & waitCount < 8) {
	Log.d(TAG,"before sleep");
try {
	Thread.sleep(500);
} catch (InterruptedException e) {
	Log.e(TAG, "Interrupted", e);
}
waitCount++;
Log.d(TAG,"after sleep");

}
waitCount = 0;
sdpDone = false;

Log.d(TAG,"returning l2c channel as "+channel);

return channel;
	}

public static void saveOppSdpRecord(SdpOppOpsRecord sdpRec, BluetoothDevice btDevice) {
	Log.d(TAG,"prp sdp complete address  is "+ btDevice.getAddress());
	Log.d(TAG,"prp mAddress  is "+ mAddress);
	if((mAddress != null) && mAddress.equalsIgnoreCase(btDevice.getAddress())) { 
		
channel = sdpRec.getL2capPsm();
sdpDone = true;


Log.d(TAG,"saveOppSdpRecord");
Log.d(TAG,"prp l2cap psm  is "+ sdpRec.getL2capPsm());
//Log.d(TAG,"prp rfcomm channel  is "+ sdpRec.getL2capPsm());

Log.d(TAG,"remote device address is "+ btDevice.getAddress());

		}
}
	
}
