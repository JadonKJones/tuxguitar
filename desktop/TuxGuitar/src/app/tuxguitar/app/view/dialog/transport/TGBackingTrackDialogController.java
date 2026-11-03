package app.tuxguitar.app.view.dialog.transport;

import app.tuxguitar.app.view.controller.TGOpenViewController;
import app.tuxguitar.app.view.controller.TGViewContext;

public class TGBackingTrackDialogController implements TGOpenViewController {

	public void openView(TGViewContext context) {
		new TGBackingTrackDialog(context).show();
	}
}
