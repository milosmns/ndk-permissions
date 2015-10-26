/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.example.nativeaudio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Spinner;
import android.widget.Toast;

public class NativeAudio extends AppCompatActivity {

    /* Load jni .so library on initialization */
    static {
        System.loadLibrary("native-audio-jni");
    }

    private static final int PERMISSION_REQUEST_RECORD = 1;
    private static final int PERMISSION_REQUEST_REVERB = 2;

    private static final int CLIP_NONE = 0;
    private static final int CLIP_HELLO = 1;
    private static final int CLIP_ANDROID = 2;
    private static final int CLIP_SAWTOOTH = 3;
    private static final int CLIP_PLAYBACK = 4;

    private static String mUri;
    private static AssetManager mAssetManager;
    private static boolean mIsPlayingAsset = false;
    private static int mNumChannelsUri = 0;
    private boolean mCreatedAudioRecorder = false;
    private boolean mReverbEnabled = false;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.main);

        mAssetManager = getAssets();

        // initialize native audio system
        createEngine();

        int sampleRate = 0;
        int bufSize = 0;
        /*
         * retrieve fast audio path sample rate and buf size; if we have it, we pass to native
         * side to create a player with fast audio enabled [ fast audio == low latency audio ];
         * IF we do not have a fast audio path, we pass 0 for sampleRate, which will force native
         * side to pick up the 8Khz sample rate.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            String nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            sampleRate = Integer.parseInt(nativeParam);
            nativeParam = myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            bufSize = Integer.parseInt(nativeParam);
        }
        createBufferQueueAudioPlayer(sampleRate, bufSize);

        // initialize URI spinner
        Spinner uriSpinner = (Spinner) findViewById(R.id.uri_spinner);
        ArrayAdapter<CharSequence> uriAdapter = ArrayAdapter.createFromResource(this, R.array.uri_spinner_array,
                android.R.layout.simple_spinner_item);
        uriAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        uriSpinner.setAdapter(uriAdapter);
        uriSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                mUri = parent.getItemAtPosition(pos).toString();
            }

            public void onNothingSelected(AdapterView parent) {
                mUri = null;
            }
        });

        // initialize button click handlers
        findViewById(R.id.hello).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                // ignore the return value
                selectClip(CLIP_HELLO, 5);
            }
        });

        findViewById(R.id.android).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                // ignore the return value
                selectClip(CLIP_ANDROID, 7);
            }
        });

        findViewById(R.id.sawtooth).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                // ignore the return value
                selectClip(CLIP_SAWTOOTH, 1);
            }
        });

        findViewById(R.id.reverb).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                int permissionCheck = ContextCompat.checkSelfPermission(NativeAudio.this, Manifest.permission.MODIFY_AUDIO_SETTINGS);
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(NativeAudio.this, new String[] {
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                    }, PERMISSION_REQUEST_REVERB);
                    return;
                }

                continueToAudioReverb();
            }
        });

        findViewById(R.id.embedded_soundtrack).setOnClickListener(new OnClickListener() {
            boolean created = false;

            public void onClick(View view) {
                if (!created) {
                    created = createAssetAudioPlayer(mAssetManager, "background.mp3");
                }
                if (created) {
                    mIsPlayingAsset = !mIsPlayingAsset;
                    setPlayingAssetAudioPlayer(mIsPlayingAsset);
                }
            }
        });

        findViewById(R.id.uri_soundtrack).setOnClickListener(new OnClickListener() {
            boolean created = false;

            public void onClick(View view) {
                if (!created && mUri != null) {
                    created = createUriAudioPlayer(mUri);
                }
            }
        });

        findViewById(R.id.pause_uri).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                setPlayingUriAudioPlayer(false);
            }
        });

        findViewById(R.id.play_uri).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                setPlayingUriAudioPlayer(true);
            }
        });

        findViewById(R.id.loop_uri).setOnClickListener(new OnClickListener() {
            boolean isLooping = false;

            public void onClick(View view) {
                isLooping = !isLooping;
                setLoopingUriAudioPlayer(isLooping);
            }
        });

        findViewById(R.id.mute_left_uri).setOnClickListener(new OnClickListener() {
            boolean muted = false;

            public void onClick(View view) {
                muted = !muted;
                setChannelMuteUriAudioPlayer(0, muted);
            }
        });

        findViewById(R.id.mute_right_uri).setOnClickListener(new OnClickListener() {
            boolean muted = false;

            public void onClick(View view) {
                muted = !muted;
                setChannelMuteUriAudioPlayer(1, muted);
            }
        });

        findViewById(R.id.solo_left_uri).setOnClickListener(new OnClickListener() {
            boolean soloed = false;

            public void onClick(View view) {
                soloed = !soloed;
                setChannelSoloUriAudioPlayer(0, soloed);
            }
        });

        findViewById(R.id.solo_right_uri).setOnClickListener(new OnClickListener() {
            boolean soloed = false;

            public void onClick(View view) {
                soloed = !soloed;
                setChannelSoloUriAudioPlayer(1, soloed);
            }
        });

        findViewById(R.id.mute_uri).setOnClickListener(new OnClickListener() {
            boolean muted = false;

            public void onClick(View view) {
                muted = !muted;
                setMuteUriAudioPlayer(muted);
            }
        });

        findViewById(R.id.enable_stereo_position_uri).setOnClickListener(new OnClickListener() {
            boolean enabled = false;

            public void onClick(View view) {
                enabled = !enabled;
                enableStereoPositionUriAudioPlayer(enabled);
            }
        });

        findViewById(R.id.channels_uri).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                if (mNumChannelsUri == 0) {
                    mNumChannelsUri = getNumChannelsUriAudioPlayer();
                }
                Toast.makeText(NativeAudio.this, "Channels: " + mNumChannelsUri, Toast.LENGTH_SHORT).show();
            }
        });

        ((SeekBar) findViewById(R.id.volume_uri)).setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            int lastProgress = 100;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (BuildConfig.DEBUG && !(progress >= 0 && progress <= 100)) {
                    throw new AssertionError();
                }
                lastProgress = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                int attenuation = 100 - lastProgress;
                int millibel = attenuation * -50;
                setVolumeUriAudioPlayer(millibel);
            }
        });

        ((SeekBar) findViewById(R.id.pan_uri)).setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            int lastProgress = 100;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (BuildConfig.DEBUG && !(progress >= 0 && progress <= 100)) {
                    throw new AssertionError();
                }
                lastProgress = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                int permille = (lastProgress - 50) * 20;
                setStereoPositionUriAudioPlayer(permille);
            }
        });

        findViewById(R.id.record).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                int permissionCheck = ContextCompat.checkSelfPermission(NativeAudio.this, Manifest.permission.RECORD_AUDIO);
                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(NativeAudio.this, new String[] {
                        Manifest.permission.RECORD_AUDIO
                    }, PERMISSION_REQUEST_RECORD);
                    return;
                }

                continueToAudioRecorderCreation();
            }
        });

        findViewById(R.id.playback).setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                // ignore the return value
                selectClip(CLIP_PLAYBACK, 3);
            }
        });

    }

    private void continueToAudioRecorderCreation() {
        if (!mCreatedAudioRecorder) {
            mCreatedAudioRecorder = createAudioRecorder();
        }

        if (mCreatedAudioRecorder) {
            startRecording();
        }
    }

    private void continueToAudioReverb() {
        mReverbEnabled = !mReverbEnabled;
        if (!enableReverb(mReverbEnabled)) {
            mReverbEnabled = !mReverbEnabled;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        String permission = permissions[0];
        int result = grantResults[0];

        if (permission.equals(Manifest.permission.RECORD_AUDIO) && result == PackageManager.PERMISSION_GRANTED
                && requestCode == PERMISSION_REQUEST_RECORD) {
            continueToAudioRecorderCreation();
        } else if (permission.equals(Manifest.permission.MODIFY_AUDIO_SETTINGS) && result == PackageManager.PERMISSION_GRANTED
                && requestCode == PERMISSION_REQUEST_REVERB) {
            continueToAudioReverb();
        }
    }

    @Override
    protected void onPause() {
        // turn off all audio
        selectClip(CLIP_NONE, 0);
        mIsPlayingAsset = false;
        setPlayingAssetAudioPlayer(false);
        setPlayingUriAudioPlayer(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    /* Native methods, implemented in JNI folder */

    public static native void createEngine();

    public static native void createBufferQueueAudioPlayer(int sampleRate, int samplesPerBuf);

    public static native boolean createAssetAudioPlayer(AssetManager assetManager, String filename);

    public static native void setPlayingAssetAudioPlayer(boolean isPlaying); // true == PLAYING, false == PAUSED

    public static native boolean createUriAudioPlayer(String uri);

    public static native void setPlayingUriAudioPlayer(boolean isPlaying);

    public static native void setLoopingUriAudioPlayer(boolean isLooping);

    public static native void setChannelMuteUriAudioPlayer(int chan, boolean mute);

    public static native void setChannelSoloUriAudioPlayer(int chan, boolean solo);

    public static native int getNumChannelsUriAudioPlayer();

    public static native void setVolumeUriAudioPlayer(int millibel);

    public static native void setMuteUriAudioPlayer(boolean mute);

    public static native void enableStereoPositionUriAudioPlayer(boolean enable);

    public static native void setStereoPositionUriAudioPlayer(int permille);

    public static native boolean selectClip(int which, int count);

    public static native boolean enableReverb(boolean enabled);

    public static native boolean createAudioRecorder();

    public static native void startRecording();

    public static native void shutdown();

}
