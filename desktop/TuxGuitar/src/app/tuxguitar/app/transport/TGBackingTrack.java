package app.tuxguitar.app.transport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

import app.tuxguitar.app.system.config.TGConfigManager;
import app.tuxguitar.player.base.MidiPlayer;
import app.tuxguitar.player.base.MidiPlayerException;
import app.tuxguitar.util.TGContext;
import app.tuxguitar.util.singleton.TGSingletonFactory;
import app.tuxguitar.util.singleton.TGSingletonUtil;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

public class TGBackingTrack {

	private static final String KEY_LATENCY = "backing-track.latency";

	private static final int HOP = 512;	// output frames per chunk
	private static final int WINDOW = HOP * 2;
	private static final int SEARCH = 256;
	private static final int LINE_FRAMES = HOP * 4;	// ~46ms output buffer
	private static final double JUMP_MS = 80;	// larger errors are seeks: jump there
	private static final double DEADBAND_MS = 3;
	private static final double NUDGE_PER_MS = 0.002;	// smooth drift correction, speed +/-0.2% per ms of error
	private static final double MAX_NUDGE = 0.03;

	private static final float[] HANN = new float[WINDOW];
	static {
		for (int i = 0; i < WINDOW; i++) {
			HANN[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / WINDOW));
		}
	}

	private TGContext context;
	private volatile String fileName;
	private volatile int offsetMs;
	private volatile int latencyMs;
	private Feeder feeder;

	private TGBackingTrack(TGContext context) {
		this.context = context;
		this.latencyMs = TGConfigManager.getInstance(context).getIntegerValue(KEY_LATENCY, 0);
	}

	public String getFileName() {
		return this.fileName;
	}

	public int getOffsetMs() {
		return this.offsetMs;
	}

	// how far into the audio file the first measure starts (intro length), may be negative
	public void setOffsetMs(int offsetMs) {
		this.offsetMs = offsetMs;
	}

	public int getLatencyMs() {
		return this.latencyMs;
	}

	// delays the audio, to match the output latency of the midi synth
	public void setLatencyMs(int latencyMs) {
		this.latencyMs = latencyMs;
		TGConfigManager config = TGConfigManager.getInstance(this.context);
		config.setValue(KEY_LATENCY, latencyMs);
		config.save();
	}

	public void load(File file) throws Exception {
		Feeder newFeeder = new Feeder(decode(file));
		synchronized (this) {
			this.unload();
			this.feeder = newFeeder;
			this.fileName = file.getName();
		}
		Thread thread = new Thread(newFeeder, "TuxGuitar backing track");
		thread.setDaemon(true);
		thread.setPriority(Thread.MAX_PRIORITY);
		thread.start();
	}

	public synchronized void unload() {
		if (this.feeder != null) {
			this.feeder.closed = true;
			this.feeder = null;
			this.fileName = null;
		}
	}

	private static class Pcm {
		short[] data;	// interleaved stereo
		float rate;
		Pcm(short[] data, float rate) { this.data = data; this.rate = rate; }
		int frames() { return this.data.length / 2; }
	}

	private static Pcm decode(File file) throws Exception {
		if (file.getName().toLowerCase().endsWith(".mp3")) {
			return decodeMp3(file);
		}
		try {
			return decodeJavaSound(file);
		} catch (UnsupportedAudioFileException e) {
			return decodeFfmpeg(file);
		}
	}

	private static Pcm decodeJavaSound(File file) throws Exception {
		try (AudioInputStream source = AudioSystem.getAudioInputStream(file)) {
			AudioFormat format = source.getFormat();
			AudioFormat target = new AudioFormat(format.getSampleRate(), 16, format.getChannels(), true, false);
			try (AudioInputStream stream = AudioSystem.getAudioInputStream(target, source)) {
				return toStereo(stream.readAllBytes(), format.getChannels(), format.getSampleRate());
			}
		}
	}

	private static Pcm decodeMp3(File file) throws Exception {
		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			Bitstream bitstream = new Bitstream(in);
			Decoder decoder = new Decoder();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			Header header;
			while ((header = bitstream.readFrame()) != null) {
				SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);
				for (int i = 0; i < buffer.getBufferLength(); i++) {
					bytes.write(buffer.getBuffer()[i]);
					bytes.write(buffer.getBuffer()[i] >> 8);
				}
				bitstream.closeFrame();
			}
			return toStereo(bytes.toByteArray(), decoder.getOutputChannels(), decoder.getOutputFrequency());
		}
	}

	// anything java can't read natively (flac, ogg, m4a...) goes through ffmpeg, when installed
	private static Pcm decodeFfmpeg(File file) throws Exception {
		Process process;
		try {
			process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", file.getAbsolutePath(), "-f", "s16le", "-ac", "2", "-ar", "44100", "pipe:1")
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		} catch (Exception e) {
			throw new Exception("Unsupported audio format: " + file.getName() + ". Use wav or mp3, or install ffmpeg for other formats.");
		}
		byte[] bytes = process.getInputStream().readAllBytes();
		if (process.waitFor() != 0) {
			throw new Exception("Could not decode " + file.getName());
		}
		return toStereo(bytes, 2, 44100);
	}

	// 16 bit little endian samples, mono is duplicated, channels beyond 2 are dropped
	private static Pcm toStereo(byte[] bytes, int channels, float rate) {
		int frames = bytes.length / (2 * channels);
		short[] data = new short[frames * 2];
		for (int f = 0; f < frames; f++) {
			for (int c = 0; c < 2; c++) {
				int i = (f * channels + Math.min(c, channels - 1)) * 2;
				data[f * 2 + c] = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
			}
		}
		return new Pcm(data, rate);
	}

	private class Feeder implements Runnable {

		volatile boolean closed;

		private final Pcm pcm;
		private final float[] tail = new float[HOP * 2];	// second half of previous windowed segment
		private final byte[] out = new byte[HOP * 4];
		private double pos;	// source frame of next chunk
		private long prevSegment = -1;	// -1: no continuity (start, seek)

		Feeder(Pcm pcm) {
			this.pcm = pcm;
		}

		public void run() {
			SourceDataLine line = null;
			try {
				AudioFormat format = new AudioFormat(this.pcm.rate, 16, 2, true, false);
				line = AudioSystem.getSourceDataLine(format);
				line.open(format, LINE_FRAMES * 4);
				boolean playing = false;
				while (!this.closed) {
					Double songMs = this.currentSongMs();
					if (songMs == null) {
						if (playing) {
							line.stop();
							line.flush();
							playing = false;
						}
						Thread.sleep(5);
						continue;
					}
					double tempo = MidiPlayer.getInstance(TGBackingTrack.this.context).getMode().getCurrentPercent() / 100.0;

					// song time at the moment the next written frame will be heard
					double bufferedMs = (line.getBufferSize() - line.available()) / 4 * 1000.0 / this.pcm.rate;
					double targetMs = songMs + (bufferedMs - TGBackingTrack.this.latencyMs) * tempo + TGBackingTrack.this.offsetMs;
					double errorMs = (this.pos * 1000.0 / this.pcm.rate) - targetMs;

					double speed = tempo;
					if (!playing || Math.abs(errorMs) > JUMP_MS) {
						this.pos = targetMs * this.pcm.rate / 1000.0;
						this.prevSegment = -1;
						Arrays.fill(this.tail, 0f);
					} else if (Math.abs(errorMs) > DEADBAND_MS) {
						speed *= 1 - Math.max(-MAX_NUDGE, Math.min(MAX_NUDGE, errorMs * NUDGE_PER_MS));
					}

					this.renderChunk(speed);
					if (!playing) {
						line.start();
						playing = true;
					}
					line.write(this.out, 0, this.out.length);
				}
			} catch (Throwable throwable) {
				throwable.printStackTrace();
			} finally {
				if (line != null) {
					line.close();
				}
			}
		}

		// song time in ms at 100% tempo, null when the song isn't audibly playing
		private Double currentSongMs() {
			try {
				MidiPlayer player = MidiPlayer.getInstance(TGBackingTrack.this.context);
				if (player.isRunning() && player.getSequencer().isRunning()) {
					Long timestamp = player.getCurrentTimestamp();
					return (timestamp != null ? timestamp.doubleValue() : null);
				}
			} catch (MidiPlayerException e) {
				// player not ready
			}
			return null;
		}

		// WSOLA: overlap-add windowed segments, each aligned to continue the previous one smoothly
		private void renderChunk(double speed) {
			long nominal = Math.round(this.pos);
			long segment = nominal;
			if (this.prevSegment >= 0) {
				long natural = this.prevSegment + HOP;
				segment = (Math.abs(natural - nominal) <= 1 ? natural : this.bestAlignment(nominal, natural));
			}
			for (int i = 0; i < HOP; i++) {
				for (int c = 0; c < 2; c++) {
					float value = this.tail[i * 2 + c] + this.sample(segment + i, c) * HANN[i];
					this.tail[i * 2 + c] = this.sample(segment + HOP + i, c) * HANN[HOP + i];
					int s = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
					this.out[(i * 2 + c) * 2] = (byte) s;
					this.out[(i * 2 + c) * 2 + 1] = (byte) (s >> 8);
				}
			}
			this.prevSegment = segment;
			this.pos += HOP * speed;
		}

		private long bestAlignment(long nominal, long natural) {
			long best = nominal;
			double bestScore = -Double.MAX_VALUE;
			for (int delta = -SEARCH; delta <= SEARCH; delta += 2) {
				long candidate = nominal + delta;
				double dot = 0, energy = 1e-9;
				for (int i = 0; i < HOP; i += 4) {
					float a = this.mono(candidate + i);
					dot += a * this.mono(natural + i);
					energy += a * a;
				}
				double score = dot / Math.sqrt(energy);
				if (score > bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
			return best;
		}

		private float sample(long frame, int channel) {
			return (frame >= 0 && frame < this.pcm.frames() ? this.pcm.data[(int) frame * 2 + channel] : 0f);
		}

		private float mono(long frame) {
			return this.sample(frame, 0) + this.sample(frame, 1);
		}
	}

	public static TGBackingTrack getInstance(TGContext context) {
		return TGSingletonUtil.getInstance(context, TGBackingTrack.class.getName(), new TGSingletonFactory<TGBackingTrack>() {
			public TGBackingTrack createInstance(TGContext context) {
				return new TGBackingTrack(context);
			}
		});
	}
}
