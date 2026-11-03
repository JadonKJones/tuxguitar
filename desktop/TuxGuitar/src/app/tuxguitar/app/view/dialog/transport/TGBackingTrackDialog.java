package app.tuxguitar.app.view.dialog.transport;

import java.io.File;

import app.tuxguitar.app.TuxGuitar;
import app.tuxguitar.app.transport.TGBackingTrack;
import app.tuxguitar.app.ui.TGApplication;
import app.tuxguitar.app.util.TGFileChooser;
import app.tuxguitar.app.view.controller.TGViewContext;
import app.tuxguitar.app.view.dialog.file.TGFileChooserDialog;
import app.tuxguitar.app.view.dialog.file.TGFileChooserHandler;
import app.tuxguitar.app.view.util.TGDialogUtil;
import app.tuxguitar.ui.UIFactory;
import app.tuxguitar.ui.event.UISelectionEvent;
import app.tuxguitar.ui.event.UISelectionListener;
import app.tuxguitar.ui.layout.UITableLayout;
import app.tuxguitar.ui.widget.UIButton;
import app.tuxguitar.ui.widget.UILabel;
import app.tuxguitar.ui.widget.UISpinner;
import app.tuxguitar.ui.widget.UIWindow;
import app.tuxguitar.util.error.TGErrorManager;

public class TGBackingTrackDialog {

	private static final int MAX_OFFSET_MS = 3600000;
	private static final int MAX_LATENCY_MS = 2000;

	private TGViewContext context;
	private UILabel fileLabel;
	private UIButton removeButton;

	public TGBackingTrackDialog(TGViewContext context){
		this.context = context;
	}

	public void show(){
		final TGBackingTrack backingTrack = TGBackingTrack.getInstance(this.context.getContext());
		final UIFactory uiFactory = TGApplication.getInstance(this.context.getContext()).getFactory();
		final UIWindow uiParent = this.context.getAttribute(TGViewContext.ATTRIBUTE_PARENT);
		final UITableLayout dialogLayout = new UITableLayout();
		final UIWindow dialog = uiFactory.createWindow(uiParent, true, false);

		dialog.setLayout(dialogLayout);
		dialog.setText(TuxGuitar.getProperty("transport.backing-track"));

		//--FILE--
		UILabel fileTitle = uiFactory.createLabel(dialog);
		fileTitle.setText(TuxGuitar.getProperty("transport.backing-track.file") + ":");
		dialogLayout.set(fileTitle, 1, 1, UITableLayout.ALIGN_LEFT, UITableLayout.ALIGN_CENTER, false, false);

		this.fileLabel = uiFactory.createLabel(dialog);
		dialogLayout.set(this.fileLabel, 1, 2, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_CENTER, true, false, 1, 1, 250f, null, null);

		UIButton chooseButton = uiFactory.createButton(dialog);
		chooseButton.setText(TuxGuitar.getProperty("transport.backing-track.choose"));
		chooseButton.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				chooseFile(backingTrack);
			}
		});
		dialogLayout.set(chooseButton, 1, 3, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, false, false);

		this.removeButton = uiFactory.createButton(dialog);
		this.removeButton.setText(TuxGuitar.getProperty("transport.backing-track.remove"));
		this.removeButton.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				backingTrack.unload();
				updateFile(backingTrack);
			}
		});
		dialogLayout.set(this.removeButton, 1, 4, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, false, false);

		//--OFFSET--
		UILabel offsetLabel = uiFactory.createLabel(dialog);
		offsetLabel.setText(TuxGuitar.getProperty("transport.backing-track.offset") + ":");
		dialogLayout.set(offsetLabel, 2, 1, UITableLayout.ALIGN_LEFT, UITableLayout.ALIGN_CENTER, false, false, 1, 2);

		final UISpinner offset = uiFactory.createSpinner(dialog);
		offset.setMinimum(-MAX_OFFSET_MS);
		offset.setMaximum(MAX_OFFSET_MS);
		offset.setIncrement(10);
		offset.setValue(backingTrack.getOffsetMs());
		offset.setToolTipText(TuxGuitar.getProperty("transport.backing-track.offset.tip"));
		offset.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				backingTrack.setOffsetMs(offset.getValue());
			}
		});
		dialogLayout.set(offset, 2, 3, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, true, false, 1, 2);

		//--LATENCY--
		UILabel latencyLabel = uiFactory.createLabel(dialog);
		latencyLabel.setText(TuxGuitar.getProperty("transport.backing-track.latency") + ":");
		dialogLayout.set(latencyLabel, 3, 1, UITableLayout.ALIGN_LEFT, UITableLayout.ALIGN_CENTER, false, false, 1, 2);

		final UISpinner latency = uiFactory.createSpinner(dialog);
		latency.setMinimum(-MAX_LATENCY_MS);
		latency.setMaximum(MAX_LATENCY_MS);
		latency.setIncrement(5);
		latency.setValue(backingTrack.getLatencyMs());
		latency.setToolTipText(TuxGuitar.getProperty("transport.backing-track.latency.tip"));
		latency.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				backingTrack.setLatencyMs(latency.getValue());
			}
		});
		dialogLayout.set(latency, 3, 3, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, true, false, 1, 2);

		//--CLOSE--
		UIButton closeButton = uiFactory.createButton(dialog);
		closeButton.setText(TuxGuitar.getProperty("close"));
		closeButton.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				dialog.dispose();
			}
		});
		dialogLayout.set(closeButton, 4, 4, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, false, false, 1, 1, 80f, 25f, null);

		this.updateFile(backingTrack);
		TGDialogUtil.openDialog(dialog, TGDialogUtil.OPEN_STYLE_CENTER | TGDialogUtil.OPEN_STYLE_PACK);
	}

	private void updateFile(TGBackingTrack backingTrack) {
		if(!this.fileLabel.isDisposed()) {
			String fileName = backingTrack.getFileName();
			this.fileLabel.setText(fileName != null ? fileName : TuxGuitar.getProperty("transport.backing-track.none"));
			this.removeButton.setEnabled(fileName != null);
		}
	}

	private void chooseFile(final TGBackingTrack backingTrack) {
		TGFileChooser.getInstance(this.context.getContext()).openChooser(new TGFileChooserHandler() {
			public void updateFileName(final String fileName) {
				fileLabel.setText(TuxGuitar.getProperty("transport.backing-track.loading"));
				// decoding takes a moment, keep the ui responsive
				new Thread(new Runnable() {
					public void run() {
						try {
							backingTrack.load(new File(fileName));
						} catch (Throwable throwable) {
							TGErrorManager.getInstance(context.getContext()).handleError(throwable);
						}
						TGApplication.getInstance(context.getContext()).getApplication().runInUiThread(new Runnable() {
							public void run() {
								updateFile(backingTrack);
							}
						});
					}
				}).start();
			}
		}, TGFileChooser.ALL_FORMATS, TGFileChooserDialog.STYLE_OPEN);
	}
}
