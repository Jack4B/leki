package pl.asystent.lekow;

import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Drukowanie / zapis do PDF bieżącej strony (raport dla lekarza). */
@CapacitorPlugin(name = "PrintPlugin")
public class PrintPlugin extends Plugin {
    @PluginMethod
    public void print(final PluginCall call) {
        final String title = call.getString("title", "Raport");
        getActivity().runOnUiThread(() -> {
            try {
                PrintManager pm = (PrintManager) getActivity().getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter = getBridge().getWebView().createPrintDocumentAdapter(title);
                pm.print(title, adapter, new PrintAttributes.Builder().build());
                call.resolve();
            } catch (Exception e) {
                call.reject("print_failed");
            }
        });
    }
}
