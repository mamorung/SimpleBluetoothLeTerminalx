package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Calendar;

import static android.content.Context.BLUETOOTH_SERVICE;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private static final String TAG = TerminalFragment.class.getSimpleName();

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private String newline = "\r\n";

    private TextView receiveText;

    private SerialService service;
    private boolean initialStart = true;
    private Connected connected = Connected.False;

    /* Gatt Server: BEGIN */
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    /* Gatt Server: END */

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");

        /* Gatt Server: BEGIN */
        mBluetoothManager = (BluetoothManager) getActivity().getSystemService(BLUETOOTH_SERVICE);
        startServer();
        /* Gatt Server: END */
    }

    /* Gatt Server: BEGIN */
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(getContext(), mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }
        mBluetoothGattServer.addService(TimeProfile.createTimeService());
    }
    private void stopServer() {
        if (mBluetoothGattServer == null) return;

        mBluetoothGattServer.close();
    }
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            long now = System.currentTimeMillis();
            if (TimeProfile.CURRENT_TIME.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read CurrentTime");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        TimeProfile.getExactTime(now, TimeProfile.ADJUST_NONE));
            } else if (TimeProfile.LOCAL_TIME_INFO.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read LocalTimeInfo");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        TimeProfile.getLocalTimeInfo(now));
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }
    };
    /* Gatt Server: END */

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();

        /* Gatt Server: BEGIN */
        stopServer();
        /* Gatt Server: END */
    }

    @Override
    public void onStart() {
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service !=null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setTypeface(Typeface.MONOSPACE);
        receiveText.setTextSize(16.0f);
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id ==R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id ==R.id.sendCurrentTime) {
            Calendar cl = Calendar.getInstance();

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            send(String.format("sudo date -s \"%s\"", sdf.format(cl.getTime())));
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            byte[] data = (str + newline).getBytes();
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private String spaces(int n) {
        String s = "";
        for (int i = 0; i < n; i++)
            s = s + " ";
        return s;
    }

    //
    // ANSI escape sequence analyzer
    // Handling below subset is enough for nRF52840 sdk17's cli module:
    /*
    ESC [ J
    ESC 7
    ESC 8
    ESC [ 1 ; 3_ m
    ESC [ m
    ESC [ 4_ m
    ESC [ K
    ESC [ H
    ESC [ 2 J
    ESC [ ? 3 l
    ESC [ 6 n
    ESC [ _ A
    ESC [ _ B
    ESC [ _ C
    ESC [ _ D
    */

    //
    private int lex_state = 0;
    private int escArg = 0;
    private void lexical_analize(String s){

        char c;
        String t;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            t = s.substring(i, i + 1);
            switch (lex_state) {
                case 0:
                    switch (c) {
                        case '\033':
                            lex_state = 1;
                            break;
                        default:
                            receiveText.append(t);
                            break;
                    }
                    break;
                case 1:
                    switch (c) {
                        case '[':
                            lex_state = 2;
                            escArg = 0;
                            break;
                        case '7':
                        case '8':
                            lex_state = 0;
                            break;
                        default:
                            lex_state = 0;
                            break;
                    }
                    break;
                case 2:
                    if (c >= '0' && c <= '9') {
                        escArg *= 10;
                        escArg += c - '0';
                    } else {
                        switch (c) {
                            case 'A':
                            case 'B':
                                lex_state = 0;
                                break;
                            case 'C':
                                if (escArg == 0)
                                    escArg = 1;
                                receiveText.append(spaces(escArg));
                                lex_state = 0;
                                break;
                            case 'D':
                                if (escArg == 0)
                                    escArg = 1;
                                receiveText.setText(receiveText.getText().toString().substring(0, receiveText.getText().length() - escArg));
                                lex_state = 0;
                                break;
                            case ';':
                                lex_state = 3;
                                escArg = 0;
                                break;
                            case 'H':
                            case 'J':
                            case 'K':
                            case 'm':
                            case 'n':
                                lex_state = 0;
                                break;
                            case '?':
                                lex_state = 4;
                                break;
                            default:
                                lex_state = 0;
                                break;
                        }
                    }
                    break;
                case 3:
                    if (c >= '0' && c <= '9') {
                        escArg *= 10;
                        escArg += c - '0';
                        lex_state = 2;
                    } else {
                        lex_state = 0;
                    }
                    break;
                case 4:
                    switch (c) {
                        case '3':
                            lex_state = 5;
                            break;
                        default:
                            lex_state = 0;
                            break;
                    }
                    break;
                case 5:
                    switch (c) {
                        case 'l':
                            lex_state = 0;
                            break;
                        default:
                            lex_state = 0;
                            break;
                    }
                    break;
                default:
                    lex_state = 0;
                    break;
            }
        }
    }

    private void receive(byte[] data) {
        lexical_analize(new String(data));
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
