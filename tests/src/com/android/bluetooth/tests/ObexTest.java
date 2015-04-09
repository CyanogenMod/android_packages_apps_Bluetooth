/*
* Copyright (C) 2014 Samsung System LSI
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.bluetooth.tests;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import javax.obex.ClientSession;
import javax.obex.HeaderSet;
import javax.obex.ObexPacket;
import javax.obex.ObexTransport;
import javax.obex.Operation;
import javax.obex.ResponseCodes;
import javax.obex.ServerSession;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.cardemulation.OffHostApduService;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelUuid;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.sdp.SdpManager;
import com.android.bluetooth.sdp.SdpMasRecord;
import com.android.bluetooth.tests.ObexTest.TestSequencer.OPTYPE;
import com.android.bluetooth.tests.ObexTest.TestSequencer.SeqStep;

/**
 * Test either using the reference ril without a modem, or using a RIL implementing the
 * BT SAP API, by providing the rild-bt socket as well as the extended API functions for SAP.
 *
 */
@TargetApi(Build.VERSION_CODES.KITKAT)
public class ObexTest extends AndroidTestCase {
    protected static String TAG = "ObexTest";
    protected static final boolean D = true;
    protected static final boolean TRACE = false;
    protected static final boolean DELAY_PASS_30_SEC = false;
    public static final long PROGRESS_INTERVAL_MS = 1000;
    private static final ObexTestParams defaultParams =
            new ObexTestParams(2*8092, 0, 2*1024*1024/10);

    private static final ObexTestParams throttle100Params =
            new ObexTestParams(2*8092, 100000, 2*1024*1024/10);

    private static final ObexTestParams smallParams =
            new ObexTestParams(2*8092, 0, 2*1024);

    private static final ObexTestParams hugeParams =
            new ObexTestParams(2*8092, 0, 100*1024*1024/1000);

    private static final int SMALL_OPERATION_COUNT = 1000/100;
    private static final int CONNECT_OPERATION_COUNT = 4500;

    private static final int L2CAP_PSM = 29; /* If SDP is not used */
    private static final int RFCOMM_CHANNEL = 29; /* If SDP is not used */

    public static final String SERVER_ADDRESS = "10:68:3F:5E:F9:2E";

    private static final String SDP_SERVER_NAME = "Samsung Server";
    private static final String SDP_CLIENT_NAME = "Samsung Client";

    private static final long SDP_FEATURES   = 0x87654321L; /* 32 bit */
    private static final int SDP_MSG_TYPES  = 0xf1;       /*  8 bit */
    private static final int SDP_MAS_ID     = 0xCA;       /*  8 bit */
    private static final int SDP_VERSION    = 0xF0C0;     /* 16 bit */
    public static final ParcelUuid SDP_UUID_OBEX_MAS = BluetoothUuid.MAS;

    private static int sSdpHandle = -1;

    private enum SequencerType {
        SEQ_TYPE_PAYLOAD,
        SEQ_TYPE_CONNECT_DISCONNECT
    }

    private Context mContext = null;
    private int mChannelType = 0;

    public static final int STEP_INDEX_HEADER = 0xF1; /*0xFE*/
    private static final int ENABLE_TIMEOUT = 5000;
    private static final int POLL_TIME = 500;

    public ObexTest() {
        super();
    }

    /**
     * Test that a connection can be established.
     * WARNING: The performance of the pipe implementation is not good. I'm only able to get a
     * throughput of around 220 kbyte/sec - less that when using Bluetooth :-)
     */
    public void testLocalPipes() {
        mContext = this.getContext();
        System.out.println("Setting up pipes...");

        PipedInputStream clientInStream = null;
        PipedOutputStream clientOutStream = null;
        PipedInputStream serverInStream = null;
        PipedOutputStream serverOutStream = null;
        ObexPipeTransport clientTransport = null;
        ObexPipeTransport serverTransport = null;

        try {
            /* Create and interconnect local pipes for transport */
            clientInStream = new PipedInputStream(5*8092);
            clientOutStream = new PipedOutputStream();
            serverInStream = new PipedInputStream(clientOutStream, 5*8092);
            serverOutStream = new PipedOutputStream(clientInStream);

            /* Create the OBEX transport objects to wrap the pipes - enable SRM */
            clientTransport = new ObexPipeTransport(clientInStream, clientOutStream, true);
            serverTransport = new ObexPipeTransport(serverInStream, serverOutStream, true);

            TestSequencer sequencer = createBtPayloadTestSequence(clientTransport, serverTransport);

            //Debug.startMethodTracing("ObexTrace");
            assertTrue(sequencer.run());
            //Debug.stopMethodTracing();
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
        }
    }

    /* Create a sequence of put/get operations with different payload sizes */
    private TestSequencer createBtPayloadTestSequence(ObexTransport clientTransport,
            ObexTransport serverTransport)
            throws IOException {
        TestSequencer sequencer = new TestSequencer(clientTransport, serverTransport);
        SeqStep step;

        step = sequencer.addStep(OPTYPE.CONNECT);

        step = sequencer.addStep(OPTYPE.PUT);
        step.mParams = defaultParams;
        step.mUseSrm = true;

        step = sequencer.addStep(OPTYPE.GET);
        step.mParams = defaultParams;
        step.mUseSrm = true;
if(true){
        step = sequencer.addStep(OPTYPE.PUT);
        step.mParams = throttle100Params;
        step.mUseSrm = true;

        step = sequencer.addStep(OPTYPE.GET);
        step.mParams = throttle100Params;
        step.mUseSrm = true;

        for(int i=0; i<SMALL_OPERATION_COUNT; i++){
            step = sequencer.addStep(OPTYPE.PUT);
            step.mParams = smallParams;
            step.mUseSrm = true;

            step = sequencer.addStep(OPTYPE.GET);
            step.mParams = smallParams;
            step.mUseSrm = true;

        }

        step = sequencer.addStep(OPTYPE.PUT);
        step.mParams = hugeParams;
        step.mUseSrm = true;

        step = sequencer.addStep(OPTYPE.GET);
        step.mParams = hugeParams;
        step.mUseSrm = true;
    }
        step = sequencer.addStep(OPTYPE.DISCONNECT);

        return sequencer;
    }

    private TestSequencer createBtConnectTestSequence(ObexTransport clientTransport,
            ObexTransport serverTransport)
            throws IOException {
        TestSequencer sequencer = new TestSequencer(clientTransport, serverTransport);
        SeqStep step;

            step = sequencer.addStep(OPTYPE.CONNECT);

            step = sequencer.addStep(OPTYPE.PUT);
            step.mParams = smallParams;
            step.mUseSrm = true;

            step = sequencer.addStep(OPTYPE.GET);
            step.mParams = smallParams;
            step.mUseSrm = true;

            step = sequencer.addStep(OPTYPE.DISCONNECT);

        return sequencer;
    }

    public void testBtServerL2cap() {
        testBtServer(BluetoothSocket.TYPE_L2CAP, false, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtServerRfcomm() {
        testBtServer(BluetoothSocket.TYPE_RFCOMM, false, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtClientL2cap() {
        testBtClient(BluetoothSocket.TYPE_L2CAP, false, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtClientRfcomm() {
        testBtClient(BluetoothSocket.TYPE_RFCOMM, false, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtServerSdpL2cap() {
        testBtServer(BluetoothSocket.TYPE_L2CAP, true, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtServerSdpRfcomm() {
        testBtServer(BluetoothSocket.TYPE_RFCOMM, true, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtClientSdpL2cap() {
        testBtClient(BluetoothSocket.TYPE_L2CAP, true, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtClientSdpRfcomm() {
        testBtClient(BluetoothSocket.TYPE_RFCOMM, true, SequencerType.SEQ_TYPE_PAYLOAD);
    }

    public void testBtServerConnectL2cap() {
        for(int i=0; i<CONNECT_OPERATION_COUNT; i++){
            Log.i(TAG, "Starting iteration " + i);
            testBtServer(BluetoothSocket.TYPE_L2CAP, true,
                    SequencerType.SEQ_TYPE_CONNECT_DISCONNECT);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Log.e(TAG,"Exception while waiting...",e);
            }
        }
    }

    public void testBtClientConnectL2cap() {
        for(int i=0; i<CONNECT_OPERATION_COUNT; i++){
            Log.i(TAG, "Starting iteration " + i);
            testBtClient(BluetoothSocket.TYPE_L2CAP, true,
                    SequencerType.SEQ_TYPE_CONNECT_DISCONNECT);
            try {
                // We give the server 100ms to allow adding SDP record
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG,"Exception while waiting...",e);
            }
        }
    }

    public void testBtServerConnectRfcomm() {
        for(int i=0; i<CONNECT_OPERATION_COUNT; i++){
            Log.i(TAG, "Starting iteration " + i);
            testBtServer(BluetoothSocket.TYPE_RFCOMM, true,
                    SequencerType.SEQ_TYPE_CONNECT_DISCONNECT);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Log.e(TAG,"Exception while waiting...",e);
            }
        }
    }

    public void testBtClientConnectRfcomm() {
        for(int i=0; i<CONNECT_OPERATION_COUNT; i++){
            Log.i(TAG, "Starting iteration " + i);
            testBtClient(BluetoothSocket.TYPE_RFCOMM, true,
                    SequencerType.SEQ_TYPE_CONNECT_DISCONNECT);
            try {
                // We give the server 100ms to allow adding SDP record
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Log.e(TAG,"Exception while waiting...",e);
            }
        }
    }

    /**
     * Create a serverSocket
     * @param type
     * @param useSdp
     * @return
     * @throws IOException
     */
    public static BluetoothServerSocket createServerSocket(int type, boolean useSdp)
            throws IOException {
        int rfcommChannel = -1;
        int l2capPsm = -1;

        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if(bt == null) {
            Log.e(TAG,"No Bluetooth Device!");
            assertTrue(false);
        }
        enableBt(bt);
        BluetoothServerSocket serverSocket=null;
        if(type == BluetoothSocket.TYPE_L2CAP) {
            if(useSdp == true) {
                serverSocket = bt.listenUsingL2capOn(L2CAP_PSM);
            } else {
                serverSocket = bt.listenUsingL2capOn(
                        BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP);
            }
            l2capPsm = serverSocket.getChannel();
        } else if(type == BluetoothSocket.TYPE_RFCOMM) {
            if(useSdp == true) {
                serverSocket = bt.listenUsingInsecureRfcommOn(
                        BluetoothAdapter.SOCKET_CHANNEL_AUTO_STATIC_NO_SDP);
            } else {
                serverSocket = bt.listenUsingInsecureRfcommOn(RFCOMM_CHANNEL);
            }
            rfcommChannel = serverSocket.getChannel();
        } else {
            fail("Invalid transport type!");
        }
        if(useSdp == true) {
            /* We use the MAP service record to be able to  */
            // TODO: We need to free this
            if(sSdpHandle < 0) {
                sSdpHandle = SdpManager.getDefaultManager().createMapMasRecord(SDP_SERVER_NAME,
                        SDP_MAS_ID, rfcommChannel, l2capPsm,
                        SDP_VERSION, SDP_MSG_TYPES, (int)(SDP_FEATURES & 0xffffffff));
            }
        }

        return serverSocket;
    }

    public static void removeSdp() {
        if(sSdpHandle > 0) {
            SdpManager.getDefaultManager().removeSdpRecord(sSdpHandle);
            sSdpHandle = -1;
        }
    }

    /**
     * Server side of a two device Bluetooth test of OBEX
     */
    private void testBtServer(int type, boolean useSdp, SequencerType sequencerType) {
        mContext = this.getContext();
        System.out.println("Starting BT Server...");

        if(TRACE) Debug.startMethodTracing("ServerSide");
        try {
            BluetoothServerSocket serverSocket=createServerSocket(type, useSdp);

            Log.i(TAG, "Waiting for client to connect");
            BluetoothSocket socket = serverSocket.accept();
            Log.i(TAG, "Client connected");

            BluetoothObexTransport serverTransport = new BluetoothObexTransport(socket);
            TestSequencer sequencer = null;
            switch(sequencerType) {
            case SEQ_TYPE_CONNECT_DISCONNECT:
                sequencer = createBtConnectTestSequence(null, serverTransport);
                break;
            case SEQ_TYPE_PAYLOAD:
                sequencer = createBtPayloadTestSequence(null, serverTransport);
                break;
            default:
                fail("Invalid sequencer type");
                break;

            }
            //Debug.startMethodTracing("ObexTrace");
            assertTrue(sequencer.run());
            //Debug.stopMethodTracing();
            // Same as below... serverTransport.close();
            // This is done by the obex server socket.close();
            serverSocket.close();
            removeSdp();
            sequencer.shutdown();
        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
        }
        if(TRACE) Debug.stopMethodTracing();
        if(DELAY_PASS_30_SEC) {
            Log.i(TAG, "\n\n\nTest done - please fetch logs within 30 seconds...\n\n\n");
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {}
        }
        Log.i(TAG, "Test done.");
    }

    /**
     * Enable Bluetooth and connect to a server socket
     * @param type
     * @param useSdp
     * @param context
     * @return
     * @throws IOException
     */
    static public BluetoothSocket connectClientSocket(int type, boolean useSdp, Context context)
            throws IOException {
        int rfcommChannel = RFCOMM_CHANNEL;
        int l2capPsm = L2CAP_PSM;

        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if(bt == null) {
            Log.e(TAG,"No Bluetooth Device!");
            assertTrue(false);
        }
        enableBt(bt);
        BluetoothDevice serverDevice = bt.getRemoteDevice(SERVER_ADDRESS);

        if(useSdp == true) {
            SdpMasRecord record = clientAwaitSdp(serverDevice, context);
            rfcommChannel = record.getRfcommCannelNumber();
            l2capPsm = record.getL2capPsm();
        }

        BluetoothSocket socket = null;
        if(type == BluetoothSocket.TYPE_L2CAP) {
            socket = serverDevice.createL2capSocket(l2capPsm);
        } else if(type == BluetoothSocket.TYPE_RFCOMM) {
            socket = serverDevice.createRfcommSocket(rfcommChannel);
        } else {
            fail("Invalid transport type!");
        }

        socket.connect();

        return socket;
    }

    /**
     * Test that a connection can be established.
     */
    private void testBtClient(int type, boolean useSdp, SequencerType sequencerType) {
        mContext = this.getContext();
        mChannelType = type;
        BluetoothSocket socket = null;
        System.out.println("Starting BT Client...");
        if(TRACE) Debug.startMethodTracing("ClientSide");
        try {
            socket = connectClientSocket(type, useSdp, mContext);

            BluetoothObexTransport clientTransport = new BluetoothObexTransport(socket);

            TestSequencer sequencer = null;
            switch(sequencerType) {
            case SEQ_TYPE_CONNECT_DISCONNECT:
                sequencer = createBtConnectTestSequence(clientTransport, null);
                break;
            case SEQ_TYPE_PAYLOAD:
                sequencer = createBtPayloadTestSequence(clientTransport, null);
                break;
            default:
                fail("Invalid test type");
                break;

            }
            //Debug.startMethodTracing("ObexTrace");
            assertTrue(sequencer.run());
            //Debug.stopMethodTracing();
            // socket.close(); shall be closed by the obex client
            sequencer.shutdown();

        } catch (IOException e) {
            Log.e(TAG, "IOException", e);
        }
        if(TRACE) Debug.stopMethodTracing();
        if(DELAY_PASS_30_SEC) {
            Log.i(TAG, "\n\n\nTest done - please fetch logs within 30 seconds...\n\n\n");
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {}
        }
        Log.i(TAG, "Test done.");
    }

    /* Using an anonymous class is not efficient, but keeps a tight code structure. */
    static class SdpBroadcastReceiver extends BroadcastReceiver {
        private SdpMasRecord mMasRecord; /* A non-optimal way of setting an object reference from
                                            a anonymous class. */
        final CountDownLatch mLatch;
        public SdpBroadcastReceiver(CountDownLatch latch) {
            mLatch = latch;
        }

        SdpMasRecord getMasRecord() {
            return mMasRecord;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive");
            String action = intent.getAction();
            if (action.equals(BluetoothDevice.ACTION_SDP_RECORD)){
                Log.v(TAG, "Received ACTION_SDP_RECORD.");
                ParcelUuid uuid = intent.getParcelableExtra(BluetoothDevice.EXTRA_UUID);
                Log.v(TAG, "Received UUID: " + uuid.toString());
                Log.v(TAG, "existing UUID: " + SDP_UUID_OBEX_MAS.toString());
                if(uuid.toString().equals(SDP_UUID_OBEX_MAS.toString())) {
                    assertEquals(SDP_UUID_OBEX_MAS.toString(), uuid.toString());
                    Log.v(TAG, " -> MAS UUID in result.");
                    SdpMasRecord record = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_SDP_RECORD);
                    assertNotNull(record);
                    Log.v(TAG, " -> record: "+record);
                    if(record.getServiceName().equals(SDP_SERVER_NAME)) {

                        assertEquals(((long)record.getSupportedFeatures())
                                &0xffffffffL, SDP_FEATURES);

                        assertEquals(record.getSupportedMessageTypes(), SDP_MSG_TYPES);

                        assertEquals(record.getProfileVersion(), SDP_VERSION);

                        assertEquals(record.getServiceName(), SDP_SERVER_NAME);

                        assertEquals(record.getMasInstanceId(), SDP_MAS_ID);

                        int status = intent.getIntExtra(BluetoothDevice.EXTRA_SDP_SEARCH_STATUS,
                                -1);
                        Log.v(TAG, " -> status: "+status);
                        mMasRecord = record;
                        mLatch.countDown();
                    } else {
                        Log.i(TAG, "Wrong service name (" + record.getServiceName()
                                + ") received, still waiting...");
                    }
                } else {
                    Log.i(TAG, "Wrong UUID received, still waiting...");
                }
            } else {
                fail("Unexpected intent received???");
            }
        }
    };


    private static SdpMasRecord clientAwaitSdp(BluetoothDevice serverDevice, Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_SDP_RECORD);
        final CountDownLatch latch = new CountDownLatch(1);
        SdpBroadcastReceiver broadcastReceiver = new SdpBroadcastReceiver(latch);

        context.registerReceiver(broadcastReceiver, filter);

        serverDevice.sdpSearch(SDP_UUID_OBEX_MAS);
        boolean waiting = true;
        while(waiting == true) {
            try {
                Log.i(TAG, "SDP Search requested - awaiting result...");
                latch.await();
                Log.i(TAG, "SDP Search reresult received - continueing.");
                waiting = false;
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted witle waiting - keep waiting.", e);
                waiting = true;
            }
        }
        context.unregisterReceiver(broadcastReceiver);
        return broadcastReceiver.getMasRecord();
    }

    /** Helper to turn BT on.
     * This method will either fail on an assert, or return with BT turned on.
     * Behavior of getState() and isEnabled() are validated along the way.
     */
    public static void enableBt(BluetoothAdapter adapter) {
        if (adapter.getState() == BluetoothAdapter.STATE_ON) {
            assertTrue(adapter.isEnabled());
            return;
        }
        assertEquals(BluetoothAdapter.STATE_OFF, adapter.getState());
        assertFalse(adapter.isEnabled());
        adapter.enable();
        for (int i=0; i<ENABLE_TIMEOUT/POLL_TIME; i++) {
            switch (adapter.getState()) {
            case BluetoothAdapter.STATE_ON:
                assertTrue(adapter.isEnabled());
                return;
            case BluetoothAdapter.STATE_OFF:
                Log.i(TAG, "STATE_OFF: Still waiting for enable to begin...");
                break;
            default:
                assertEquals(BluetoothAdapter.STATE_TURNING_ON, adapter.getState());
                assertFalse(adapter.isEnabled());
                break;
            }
            try {
                Thread.sleep(POLL_TIME);
            } catch (InterruptedException e) {}
        }
        fail("enable() timeout");
        Log.i(TAG, "Bluetooth enabled...");
    }

    public static class TestSequencer implements Callback {

        private final static int MSG_ID_TIMEOUT = 0x01;
        private final static int TIMEOUT_VALUE = 100*2000; // ms
        private ArrayList<SeqStep> mSequence = null;
        private HandlerThread mHandlerThread = null;
        private Handler mMessageHandler = null;
        private ObexTransport mClientTransport;
        private ObexTransport mServerTransport;

        private ClientSession mClientSession;
        private ServerSession mServerSession;
        ObexTestDataHandler mDataHandler;

        public enum OPTYPE {CONNECT, PUT, GET, DISCONNECT};


        public class SeqStep {
            /**
             * Test step class to define the operations to be tested.
             * Some of the data in these test steps will be modified during
             * test - e.g. the HeaderSets will be modified to enable SRM
             * and/or carry test information
             */
            /* Operation type - Connect, Get, Put etc. */
            public OPTYPE mType;
            /* The headers to send in the request - and validate on server side */
            public HeaderSet mReqHeaders = null;
            /* The headers to send in the response - and validate on client side */
            public HeaderSet mResHeaders = null;
            /* Use SRM */
            public boolean mUseSrm = false;
            /* The amount of data to include in the body */
            public ObexTestParams mParams = null;
            /* The offset into the data where the un-pause signal is to be sent */
            public int mUnPauseOffset = -1;
            /* The offset into the data where the Abort request is to be sent */
            public int mAbortOffset = -1;
            /* The side to perform Abort */
            public boolean mServerSideAbout = false;
            /* The ID of the test step */
            private int mId;

            /* Arrays to hold expected sequence of request/response packets. */
            public ArrayList<ObexPacket> mRequestPackets = null;
            public ArrayList<ObexPacket> mResponsePackets = null;

            public int index = 0; /* requests with same index are executed in parallel
                                     (without waiting for a response) */

            public SeqStep(OPTYPE type) {
                mRequestPackets = new ArrayList<ObexPacket>();
                mResponsePackets = new ArrayList<ObexPacket>();
                mType = type;
            }

            /* TODO: Consider to build these automatically based on the operations
             *       to be performed. Validate using utility functions - not strict
             *       binary compare.*/
            public void addObexPacketSet(ObexPacket request, ObexPacket response) {
                mRequestPackets.add(request);
                mResponsePackets.add(response);
            }
        }

        public TestSequencer(ObexTransport clientTransport, ObexTransport serverTransport)
                throws IOException {
            /* Setup the looper thread to handle messages */
//            mHandlerThread = new HandlerThread("TestTimeoutHandler",
//                      android.os.Process.THREAD_PRIORITY_BACKGROUND);
//            mHandlerThread.start();
//            Looper testLooper = mHandlerThread.getLooper();
//            mMessageHandler = new Handler(testLooper, this);
            mClientTransport = clientTransport;
            mServerTransport = serverTransport;

            //TODO: fix looper cleanup on server - crash after 464 iterations - related to prepare?

            /* Initialize members */
            mSequence = new ArrayList<SeqStep>();
            mDataHandler = new ObexTestDataHandler("(Client)");
        }

        /**
         * Add a test step to the sequencer.
         * @param type the OBEX operation to perform.
         * @return the created step, which can be decorated before execution.
         */
        public SeqStep addStep(OPTYPE type) {
            SeqStep newStep = new SeqStep(type);
            mSequence.add(newStep);
            return newStep;
        }

        /**
         * Add a sub-step to a sequencer step. All requests added to the same index will be send to
         * the SapServer in the order added before listening for the response.
         * The response order is not validated - hence for each response received the entire list of
         * responses in the step will be searched for a match.
         * @param index the index returned from addStep() to which the sub-step is to be added.
         * @param request The request to send to the SAP server
         * @param response The response to EXPECT from the SAP server

        public void addSubStep(int index, SapMessage request, SapMessage response) {
            SeqStep step = sequence.get(index);
            step.add(request, response);
        }*/


        /**
         * Run the sequence.
         * Validate the response is either the expected response or one of the expected events.
         *
         * @return true when done - asserts at error/fail
         */
        public boolean run() throws IOException {
            CountDownLatch stopLatch = new CountDownLatch(1);

            /* TODO:
             * First create sequencer to validate using BT-snoop
             * 1) Create the transports (this could include a validation sniffer on each side)
             * 2) Create a server thread with a link to the transport
             * 3) execute the client operation
             * 4) validate response
             *
             * On server:
             * 1) validate the request contains the expected content
             * 2) send response.
             * */

            /* Create the server */
            if(mServerTransport != null) {
                mServerSession = new ServerSession(mServerTransport, new ObexTestServer(mSequence,
                        stopLatch), null);
            }

            /* Create the client */
            if(mClientTransport != null) {
                mClientSession = new ClientSession(mClientTransport);

                for(SeqStep step : mSequence) {
                    long stepIndex = mSequence.indexOf(step);

                    Log.i(TAG, "Executing step " + stepIndex + " of type: " + step.mType);

                    switch(step.mType) {
                    case CONNECT: {
                        HeaderSet reqHeaders = step.mReqHeaders;
                        if(reqHeaders == null) {
                            reqHeaders = new HeaderSet();
                        }
                        reqHeaders.setHeader(STEP_INDEX_HEADER, stepIndex);
                        HeaderSet response = mClientSession.connect(reqHeaders);
                        validateHeaderSet(response, step.mResHeaders);
                        break;
                    }
                    case GET:{
                        HeaderSet reqHeaders = step.mReqHeaders;
                        if(reqHeaders == null) {
                            reqHeaders = new HeaderSet();
                        }
                        reqHeaders.setHeader(STEP_INDEX_HEADER, stepIndex);
                        Operation op = mClientSession.get(reqHeaders);
                        if(op != null) {
                            op.noBodyHeader();
                            mDataHandler.readData(op.openDataInputStream(), step.mParams);
                            int responseCode = op.getResponseCode();
                            Log.i(TAG, "response code: " + responseCode);
                            HeaderSet response = op.getReceivedHeader();
                            validateHeaderSet(response, step.mResHeaders);
                            op.close();
                        }
                        break;
                    }
                    case PUT: {
                        HeaderSet reqHeaders = step.mReqHeaders;
                        if(reqHeaders == null) {
                            reqHeaders = new HeaderSet();
                        }
                        reqHeaders.setHeader(STEP_INDEX_HEADER, stepIndex);
                        Operation op = mClientSession.put(reqHeaders);
                        if(op != null) {
                            mDataHandler.writeData(op.openDataOutputStream(), step.mParams);
                            int responseCode = op.getResponseCode();
                            Log.i(TAG, "response code: " + responseCode);
                            HeaderSet response = op.getReceivedHeader();
                            validateHeaderSet(response, step.mResHeaders);
                            op.close();
                        }
                        break;
                    }
                    case DISCONNECT: {
                        Log.i(TAG,"Requesting disconnect...");
                        HeaderSet reqHeaders = step.mReqHeaders;
                        if(reqHeaders == null) {
                            reqHeaders = new HeaderSet();
                        }
                        reqHeaders.setHeader(STEP_INDEX_HEADER, stepIndex);
                        try{
                            HeaderSet response = mClientSession.disconnect(reqHeaders);
                            Log.i(TAG,"Received disconnect response...");
                            // For some reason this returns -1 -> EOS
                            // Maybe increase server timeout.
                            validateHeaderSet(response, step.mResHeaders);
                        } catch (IOException e) {
                            Log.e(TAG, "Error getting response code", e);
                        }
                        break;
                    }
                    default:
                        assertTrue("Unknown type: " + step.mType, false);
                        break;

                    }
                }
                mClientSession.close();
            }
            /* All done, close down... */
            if(mServerSession != null) {
                boolean interrupted = false;
                do {
                    try {
                        interrupted = false;
                        Log.i(TAG,"Waiting for stopLatch signal...");
                        stopLatch.await();
                    } catch (InterruptedException e) {
                        Log.w(TAG,e);
                        interrupted = true;
                    }
                } while (interrupted == true);
                Log.i(TAG,"stopLatch signal received closing down...");
                try {
                    interrupted = false;
                    Log.i(TAG,"  Sleep 50ms to allow disconnect signal to be send before closing.");
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Log.w(TAG,e);
                    interrupted = true;
                }
                mServerSession.close();
            }
            // this will close the I/O streams as well.
            return true;
        }

        public void shutdown() {
//            mMessageHandler.removeCallbacksAndMessages(null);
//            mMessageHandler.quit();
//            mMessageHandler = null;
        }


        void validateHeaderSet(HeaderSet headers, HeaderSet expected) throws IOException {
            /* TODO: Implement and assert if different */
            if(headers.getResponseCode() != ResponseCodes.OBEX_HTTP_OK) {
                Log.e(TAG,"Wrong ResponseCode: " + headers.getResponseCode());
                assertTrue(false);
            }
        }

//        private void startTimer() {
//            Message timeoutMessage = mMessageHandler.obtainMessage(MSG_ID_TIMEOUT);
//            mMessageHandler.sendMessageDelayed(timeoutMessage, TIMEOUT_VALUE);
//        }
//
//        private void stopTimer() {
//            mMessageHandler.removeMessages(MSG_ID_TIMEOUT);
//        }

        @Override
        public boolean handleMessage(Message msg) {

            Log.i(TAG,"Handling message ID: " + msg.what);

            switch(msg.what) {
            case MSG_ID_TIMEOUT:
                Log.w(TAG, "Timeout occured!");
/*                try {
                    //inStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close inStream", e);
                }
                try {
                    //outStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "failed to close outStream", e);
                }*/
                break;
            default:
                /* Message not handled */
                return false;
            }
            return true; // Message handles
        }



    }

}