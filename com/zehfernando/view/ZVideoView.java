/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zehfernando.view;

import java.io.IOException;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;

import com.zehfernando.utils.F;

/**
 * Displays a video file.  The VideoView class
 * can load images from various sources (such as resources or content
 * providers), takes care of computing its measurement from the video so that
 * it can be used in any layout manager, and provides various display options
 * such as scaling and tinting.
 */
public class ZVideoView extends SurfaceView implements MediaPlayerControl {
	private final String TAG = "VideoView";
	// settable by the client
	private Uri		 mUri;
	private int		 mDuration;

	// all possible internal states
	private static final int STATE_ERROR			  = -1;
	private static final int STATE_IDLE			   = 0;
	private static final int STATE_PREPARING		  = 1;
	private static final int STATE_PREPARED		   = 2;
	private static final int STATE_PLAYING			= 3;
	private static final int STATE_PAUSED			 = 4;
	private static final int STATE_PLAYBACK_COMPLETED = 5;
	private static final int STATE_SUSPEND			= 6;
	private static final int STATE_RESUME			 = 7;
	private static final int STATE_SUSPEND_UNSUPPORTED = 8;

	// mCurrentState is a VideoView object's current state.
	// mTargetState is the state that a method caller intends to reach.
	// For instance, regardless the VideoView object's current state,
	// calling pause() intends to bring the object to a target state
	// of STATE_PAUSED.
	private int mCurrentState = STATE_IDLE;
	private int mTargetState  = STATE_IDLE;

	// All the stuff we need for playing and showing a video
	private SurfaceHolder mSurfaceHolder = null;
	private MediaPlayer mMediaPlayer = null;
	private int		 mVideoWidth;
	private int		 mVideoHeight;
	private int		 mSurfaceWidth;
	private int		 mSurfaceHeight;
	private MediaController mMediaController;
	private OnCompletionListener mOnCompletionListener;
	private OnBufferedListener mOnBufferedListener;
	private MediaPlayer.OnPreparedListener mOnPreparedListener;
	private MediaPlayer.OnSeekCompleteListener mOnSeekCompleteListener;
	private MediaPlayer.OnBufferingUpdateListener mOnBufferingUpdateListener;
	private int		 mCurrentBufferPercentage;
	private OnErrorListener mOnErrorListener;
	private int		 mSeekWhenPrepared;  // recording the seek position while preparing
	private boolean	 mCanPause;
	private boolean	 mCanSeekBack;
	private boolean	 mCanSeekForward;
	private boolean _prepareAsynchronously;
	//private int		 mStateWhenSuspended;  //state before calling suspend()

	private boolean mustPauseOnSeek = false;

	protected boolean _isBeingBuffered = false;
	protected boolean _isBuffered = false;

	protected Context mContext;

	public ZVideoView(Context context) {
		super(context);
		mContext = context;
		initVideoView();
	}

	public ZVideoView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
		mContext = context;
		initVideoView();
	}

	public ZVideoView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		mContext = context;
		initVideoView();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		//Log.i("@@@@", "onMeasure");
		int width = getDefaultSize(mVideoWidth, widthMeasureSpec);
		int height = getDefaultSize(mVideoHeight, heightMeasureSpec);

		if (mVideoWidth > 0 && mVideoHeight > 0) {
			if ( mVideoWidth * height > width * mVideoHeight ) {
				//Log.i("@@@", "image too tall, correcting");
				height = width * mVideoHeight / mVideoWidth;
			} else if ( mVideoWidth * height  < width * mVideoHeight ) {
				//Log.i("@@@", "image too wide, correcting");
				width = height * mVideoWidth / mVideoHeight;
			} else {
				//Log.i("@@@", "aspect ratio is correct: " +
						//width+"/"+height+"="+
						//mVideoWidth+"/"+mVideoHeight);
			}
		}

		//Log.i("@@@@@@@@@@", "setting size: " + width + 'x' + height);
		setMeasuredDimension(width, height);
	}

	public int resolveAdjustedSize(int desiredSize, int measureSpec) {
		int result = desiredSize;
		int specMode = MeasureSpec.getMode(measureSpec);
		int specSize =  MeasureSpec.getSize(measureSpec);

		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				/* Parent says we can be as big as we want. Just don't be larger
				 * than max size imposed on ourselves.
				 */
				result = desiredSize;
				break;

			case MeasureSpec.AT_MOST:
				/* Parent says we can be as big as we want, up to specSize.
				 * Don't be larger than specSize, and don't be larger than
				 * the max size imposed on ourselves.
				 */
				result = Math.min(desiredSize, specSize);
				break;

			case MeasureSpec.EXACTLY:
				// No choice. Do what we are told.
				result = specSize;
				break;
		}
		return result;
}

	private void initVideoView() {
		mVideoWidth = 0;
		mVideoHeight = 0;
		getHolder().addCallback(mSHCallback);
		getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		setFocusable(true);
		setFocusableInTouchMode(true);
		requestFocus();
		_prepareAsynchronously = true;
		mCurrentState = STATE_IDLE;
		mTargetState  = STATE_IDLE;
	}

	public void setVideoPath(String path) {
		setVideoURI(Uri.parse(path));
	}

	public void setVideoURI(Uri uri) {
		mUri = uri;
		mSeekWhenPrepared = 0;
		openVideo();
		requestLayout();
		invalidate();
	}

	/*
	public void stop() {
		if (mMediaPlayer != null) {
			mMediaPlayer.stop();
			mCurrentState = STATE_IDLE;
			mTargetState  = STATE_IDLE;
		}
	}
	*/

	public void stopPlayback() {
		if (mMediaPlayer != null) {
			_isBeingBuffered = false;
			_isBuffered = false;
			mMediaPlayer.stop();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			mTargetState  = STATE_IDLE;
		}
	}

	private void openVideo() {
		if (mUri == null || mSurfaceHolder == null) {
			// not ready for playback just yet, will try again later
			return;
		}
		// Tell the music playback service to pause
		// TODO: these constants need to be published somewhere in the framework.
//		Intent i = new Intent("com.android.music.musicservicecommand");
//		i.putExtra("command", "pause");
//		mContext.sendBroadcast(i); // [zeh]

		// we shouldn't clear the target state, because somebody might have
		// called start() previously
		release(false);
		try {
			mMediaPlayer = new MediaPlayer();
			mMediaPlayer.setOnVideoSizeChangedListener(mSizeChangedListener);
			mDuration = -1;
			mMediaPlayer.setOnCompletionListener(mCompletionListener);
			mMediaPlayer.setOnErrorListener(mErrorListener);
			mMediaPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
			mMediaPlayer.setOnSeekCompleteListener(mSeekCompleteListener); // [zeh]
			mMediaPlayer.setOnInfoListener(mInfoListener);
			mCurrentBufferPercentage = 0;
			//mMediaPlayer.setDataSource(mContext, mUri, mHeaders); // [zeh]
			mMediaPlayer.setDataSource(mContext, mUri);
			mMediaPlayer.setDisplay(mSurfaceHolder);
			mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
			mMediaPlayer.setScreenOnWhilePlaying(true);
			mCurrentState = STATE_PREPARING;
			if (_prepareAsynchronously) {
				mMediaPlayer.setOnPreparedListener(mPreparedListener);
				mMediaPlayer.prepareAsync();
			} else {
				mMediaPlayer.prepare();
			}
			// we don't set the target state here either, but preserve the
			// target state that was there before.
			attachMediaController();
		} catch (IOException ex) {
			Log.w(TAG, "Unable to open content: " + mUri, ex);
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			return;
		} catch (IllegalArgumentException ex) {
			Log.w(TAG, "Unable to open content: " + mUri, ex);
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			mErrorListener.onError(mMediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
			return;
		}

		if (!_prepareAsynchronously) mPreparedListener.onPrepared(mMediaPlayer);
	}

	public void setMediaController(MediaController controller) {
		if (mMediaController != null) {
			mMediaController.hide();
		}
		mMediaController = controller;
		attachMediaController();
	}

	private void attachMediaController() {
		if (mMediaPlayer != null && mMediaController != null) {
			mMediaController.setMediaPlayer(this);
			View anchorView = this.getParent() instanceof View ? (View)this.getParent() : this;
			mMediaController.setAnchorView(anchorView);
			mMediaController.setEnabled(isInPlaybackState());
		}
	}

	MediaPlayer.OnVideoSizeChangedListener mSizeChangedListener =
		new MediaPlayer.OnVideoSizeChangedListener() {
			@Override
			public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
				Log.v("ZVideoView", "video size changed: " + width + ", " + height);
				mVideoWidth = mp.getVideoWidth();
				mVideoHeight = mp.getVideoHeight();
				if (mVideoWidth != 0 && mVideoHeight != 0) {
					getHolder().setFixedSize(mVideoWidth, mVideoHeight);
				}
			}
	};

	MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
		@Override
		public void onPrepared(MediaPlayer mp) {
			mCurrentState = STATE_PREPARED;

			// Get the capabilities of the player for this stream
			// [zeh] -- START
			/*
			Metadata data = mp.getMetadata(MediaPlayer.METADATA_ALL,
									  MediaPlayer.BYPASS_METADATA_FILTER);

			if (data != null) {
				mCanPause = !data.has(Metadata.PAUSE_AVAILABLE)
						|| data.getBoolean(Metadata.PAUSE_AVAILABLE);
				mCanSeekBack = !data.has(Metadata.SEEK_BACKWARD_AVAILABLE)
						|| data.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE);
				mCanSeekForward = !data.has(Metadata.SEEK_FORWARD_AVAILABLE)
						|| data.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE);
			} else {
				mCanPause = mCanSeekBack = mCanSeekForward = true;
			}
			*/
			// [zeh] -- END
			mCanPause = mCanSeekBack = mCanSeekForward = true;

			if (mOnPreparedListener != null) mOnPreparedListener.onPrepared(mMediaPlayer);
			if (mMediaController != null) mMediaController.setEnabled(true);

			mVideoWidth = mp.getVideoWidth();
			mVideoHeight = mp.getVideoHeight();

			int seekToPosition = mSeekWhenPrepared;  // mSeekWhenPrepared may be changed after seekTo() call
			if (seekToPosition != 0) seekTo(seekToPosition);
			if (mVideoWidth != 0 && mVideoHeight != 0) {
				//Log.i("@@@@", "video size: " + mVideoWidth +"/"+ mVideoHeight);
				getHolder().setFixedSize(mVideoWidth, mVideoHeight);
				if (mSurfaceWidth == mVideoWidth && mSurfaceHeight == mVideoHeight) {
					// We didn't actually change the size (it was already at the size
					// we need), so we won't get a "surface changed" callback, so
					// start the video here instead of in the callback.
					if (mTargetState == STATE_PLAYING) {
						start(_isBeingBuffered);
						if (mMediaController != null) mMediaController.show();
					} else if (!isPlaying() && (seekToPosition != 0 || getCurrentPosition() > 0)) {
					   if (mMediaController != null) {
						   // Show the media controls when we're paused into a video and make 'em stick.
						   mMediaController.show(0);
					   }
				   }
				}
			} else {
				// We don't know the video size yet, but should start anyway.
				// The video size might be reported to us later.
				if (mTargetState == STATE_PLAYING) {
					start();
				}
			}
		}
	};

	public interface OnBufferedListener {
		public void onBuffered(MediaPlayer __mediaPlayer);
	}

	private final MediaPlayer.OnCompletionListener mCompletionListener =
		new MediaPlayer.OnCompletionListener() {
		@Override
		public void onCompletion(MediaPlayer mp) {
			mCurrentState = STATE_PLAYBACK_COMPLETED;
			mTargetState = STATE_PLAYBACK_COMPLETED;
			if (mMediaController != null) {
				mMediaController.hide();
			}
			F.log("Completed :: " + _isBeingBuffered + ", " + _isBuffered);
			if (_isBeingBuffered) {
				_isBeingBuffered = false;
				_isBuffered = true;
				seekTo(0);
				if (mOnBufferedListener != null) mOnBufferedListener.onBuffered(mMediaPlayer);
			} else {
				if (mOnCompletionListener != null) mOnCompletionListener.onCompletion(mMediaPlayer);
			}
		}
	};

	private final MediaPlayer.OnErrorListener mErrorListener =
		new MediaPlayer.OnErrorListener() {
		@Override
		public boolean onError(MediaPlayer mp, int framework_err, int impl_err) {
			Log.d(TAG, "Error: " + framework_err + "," + impl_err);
			mCurrentState = STATE_ERROR;
			mTargetState = STATE_ERROR;
			if (mMediaController != null) {
				mMediaController.hide();
			}

			/* If an error handler has been supplied, use it and finish. */
			if (mOnErrorListener != null) {
				if (mOnErrorListener.onError(mMediaPlayer, framework_err, impl_err)) {
					return true;
				}
			}

			/* Otherwise, pop up an error dialog so the user knows that
			 * something bad has happened. Only try and pop up the dialog
			 * if we're attached to a window. When we're going away and no
			 * longer have a window, don't bother showing the user an error.
			 */
			if (getWindowToken() != null) {
				//Resources r = mContext.getResources();
				int messageId;

				if (framework_err == MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK) {
					messageId = android.R.string.VideoView_error_text_invalid_progressive_playback;
				} else {
					messageId = android.R.string.VideoView_error_text_unknown;
				}

				new AlertDialog.Builder(mContext)
						.setTitle(android.R.string.VideoView_error_title)
						.setMessage(messageId)
						.setPositiveButton(android.R.string.VideoView_error_button,
								new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog, int whichButton) {
										/* If we get here, there is no onError listener, so
										 * at least inform them that the video is over.
										 */
										if (mOnCompletionListener != null) {
											mOnCompletionListener.onCompletion(mMediaPlayer);
										}
									}
								})
						.setCancelable(false)
						.show();
			}
			return true;
		}
	};

	private final MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener = new MediaPlayer.OnBufferingUpdateListener() {
		@Override
		public void onBufferingUpdate(MediaPlayer mp, int percent) {
			mCurrentBufferPercentage = percent;

			Log.v("ZVideoView", "OnBufferingUpdate: " + percent);

			if (mOnBufferingUpdateListener != null) {
				mOnBufferingUpdateListener.onBufferingUpdate(mp, percent);
			}
		}
	};

	private final MediaPlayer.OnInfoListener mInfoListener = new MediaPlayer.OnInfoListener() {
		@Override
		public boolean onInfo(MediaPlayer mp, int what, int extra) {
			Log.v("ZVideoView", "onInfo: " + what + " = " + extra);

			return false;
		}
	};

	private final MediaPlayer.OnSeekCompleteListener mSeekCompleteListener = new MediaPlayer.OnSeekCompleteListener() {
		@Override
		public void onSeekComplete(MediaPlayer mp) {
			// [zeh]
			if (mustPauseOnSeek) {
				//mMediaPlayer.pause();
				pause();
				mustPauseOnSeek = false;
			}

			if (mOnSeekCompleteListener != null) {
				mOnSeekCompleteListener.onSeekComplete(mMediaPlayer);
			}
		}
	};

	/**
	 * Register a callback to be invoked when the media file
	 * is loaded and ready to go.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnPreparedListener(MediaPlayer.OnPreparedListener l) {
		mOnPreparedListener = l;
	}

	public void setOnSeekCompleteListener(MediaPlayer.OnSeekCompleteListener l) {
		mOnSeekCompleteListener = l;
	}

	public void setOnBufferingUpdateListener(MediaPlayer.OnBufferingUpdateListener l) {
		mOnBufferingUpdateListener = l;
	}

	/**
	 * Register a callback to be invoked when the end of a media file
	 * has been reached during playback.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnCompletionListener(OnCompletionListener l) {
		mOnCompletionListener = l;
	}

	public void setOnBufferedListener(OnBufferedListener l) {
		mOnBufferedListener = l;
	}

	/**
	 * Register a callback to be invoked when an error occurs
	 * during playback or setup.  If no listener is specified,
	 * or if the listener returned false, VideoView will inform
	 * the user of any errors.
	 *
	 * @param l The callback that will be run
	 */
	public void setOnErrorListener(OnErrorListener l)
	{
		mOnErrorListener = l;
	}

	SurfaceHolder.Callback mSHCallback = new SurfaceHolder.Callback()
	{
		@Override
		public void surfaceChanged(SurfaceHolder holder, int format,
									int w, int h)
		{
			mSurfaceWidth = w;
			mSurfaceHeight = h;
			boolean isValidState =  (mTargetState == STATE_PLAYING);
			boolean hasValidSize = (mVideoWidth == w && mVideoHeight == h);
			if (mMediaPlayer != null && isValidState && hasValidSize) {
				if (mSeekWhenPrepared != 0) {
					seekTo(mSeekWhenPrepared);
				}
				start();
				if (mMediaController != null) {
					if (mMediaController.isShowing()) {
						// ensure the controller will get repositioned later
						mMediaController.hide();
					}
					mMediaController.show();
				}
			}
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder)
		{
			mSurfaceHolder = holder;
			//resume() was called before surfaceCreated()
			if (mMediaPlayer != null && mCurrentState == STATE_SUSPEND
				   && mTargetState == STATE_RESUME) {
				mMediaPlayer.setDisplay(mSurfaceHolder);
				resume();
			} else {
				openVideo();
			}
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder)
		{
			// after we return from this we can't use the surface any more
			mSurfaceHolder = null;
			if (mMediaController != null) mMediaController.hide();
			if (mCurrentState != STATE_SUSPEND) {
				release(true);
			}
		}
	};

	/*
	 * release the media player in any state
	 */
	private void release(boolean cleartargetstate) {
		if (mMediaPlayer != null) {
			mMediaPlayer.reset();
			mMediaPlayer.release();
			mMediaPlayer = null;
			mCurrentState = STATE_IDLE;
			if (cleartargetstate) {
				mTargetState  = STATE_IDLE;
			}
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@Override
	public boolean onTrackballEvent(MotionEvent ev) {
		if (isInPlaybackState() && mMediaController != null) {
			toggleMediaControlsVisiblity();
		}
		return false;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event)
	{
		boolean isKeyCodeSupported = keyCode != KeyEvent.KEYCODE_BACK &&
									 keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
									 keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
									 keyCode != KeyEvent.KEYCODE_MENU &&
									 keyCode != KeyEvent.KEYCODE_CALL &&
									 keyCode != KeyEvent.KEYCODE_ENDCALL;
		if (isInPlaybackState() && isKeyCodeSupported && mMediaController != null) {
			if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
					keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
				if (mMediaPlayer.isPlaying()) {
					pause();
					mMediaController.show();
				} else {
					start();
					mMediaController.hide();
				}
				return true;
			} else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP
					&& mMediaPlayer.isPlaying()) {
				pause();
				mMediaController.show();
			} else {
				toggleMediaControlsVisiblity();
			}
		}

		return super.onKeyDown(keyCode, event);
	}

	private void toggleMediaControlsVisiblity() {
		if (mMediaController.isShowing()) {
			mMediaController.hide();
		} else {
			mMediaController.show();
		}
	}

	@Override
	public void start() {
		start(false);
	}

	public void start(boolean __bufferMode) {
		_isBeingBuffered = __bufferMode;

		if (isInPlaybackState()) {
			F.log("buffer = " + _isBeingBuffered);
			mMediaPlayer.start();
			mCurrentState = STATE_PLAYING;
		}
		mTargetState = STATE_PLAYING;
	}

	public void buffer() {
		F.log();
		if (!isBeingBuffered()) {
			_isBuffered = false;
			start(true);
			seekTo(0);
		}
	}

	public boolean isBeingBuffered() {
		return _isBeingBuffered;
	}

	public boolean isBuffered() {
		return _isBuffered;
	}

	@Override
	public void pause() {
		if (isInPlaybackState()) {
			if (mMediaPlayer.isPlaying()) {
				mMediaPlayer.pause();
				mCurrentState = STATE_PAUSED;
			}
		}
		mTargetState = STATE_PAUSED;
	}

	public void suspend() {
		if (isInPlaybackState()) {
			// [zeh] -- START
			/*
			if (mMediaPlayer.suspend()) {
				mStateWhenSuspended = mCurrentState;
				mCurrentState = STATE_SUSPEND;
				mTargetState = STATE_SUSPEND;
			} else {
			// [zeh] -- END
			*/
				release(false);
				mCurrentState = STATE_SUSPEND_UNSUPPORTED;
				Log.w(TAG, "Unable to suspend video. Release MediaPlayer.");
			//}
		}
	}

	public void resume() {
		if (mSurfaceHolder == null && mCurrentState == STATE_SUSPEND){
			mTargetState = STATE_RESUME;
			return;
		}
		// [zeh] -- START
		/*
		if (mMediaPlayer != null && mCurrentState == STATE_SUSPEND) {
			if (mMediaPlayer.resume()) {
				mCurrentState = mStateWhenSuspended;
				mTargetState = mStateWhenSuspended;
			} else {
				Log.w(TAG, "Unable to resume video");
			}
			return;
		}
	   	// [zeh] -- END
		*/
		if (mCurrentState == STATE_SUSPEND_UNSUPPORTED) {
			openVideo();
		}
	}

   // cache duration as mDuration for faster access
	@Override
	public int getDuration() {
		if (isInPlaybackState()) {
			if (mDuration > 0) {
				return mDuration;
			}
			mDuration = mMediaPlayer.getDuration();
			return mDuration;
		}
		mDuration = -1;
		return mDuration;
	}

	@Override
	public int getCurrentPosition() {
		if (isInPlaybackState()) {
			return mMediaPlayer.getCurrentPosition();
		}
		return 0;
	}

	@Override
	public void seekTo(int msec) {
		if (isInPlaybackState()) {
			mMediaPlayer.seekTo(msec);
			mSeekWhenPrepared = 0;
		} else {
			mSeekWhenPrepared = msec;
		}
	}

	@Override
	public boolean isPlaying() {
		return isInPlaybackState() && mMediaPlayer.isPlaying();
	}

	@Override
	public int getBufferPercentage() {
		if (mMediaPlayer != null) {
			return mCurrentBufferPercentage;
		}
		return 0;
	}

	private boolean isInPlaybackState() {
		return (mMediaPlayer != null &&
				mCurrentState != STATE_ERROR &&
				mCurrentState != STATE_IDLE &&
				mCurrentState != STATE_PREPARING);
	}

	@Override
	public boolean canPause() {
		return mCanPause;
	}

	@Override
	public boolean canSeekBackward() {
		return mCanSeekBack;
	}

	@Override
	public boolean canSeekForward() {
		return mCanSeekForward;
	}

	public void setMustPauseOnSeek(boolean __mustPause) {
		mustPauseOnSeek = __mustPause;
	}

	public MediaPlayer getMediaPlayer() {
		return mMediaPlayer;
	}

	public boolean getPrepareAsynchronously() {
		return _prepareAsynchronously;
	}

	public void setPrepareAsynchronously(boolean __value) {
		_prepareAsynchronously = __value;
	}
}