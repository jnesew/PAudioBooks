import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.MethodEntryRequest;

/** Development-only public JDI driver. Never packaged into an Android artifact. */
public final class HoldInspectionWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("[0-9]+")) throw new IllegalArgumentException("Expected forwarded JDWP port");
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var parameters = connector.defaultArguments();
        parameters.get("hostname").setValue("127.0.0.1");
        parameters.get("port").setValue(args[0]);
        parameters.get("timeout").setValue("5000");
        VirtualMachine vm = connector.attach(parameters);
        boolean held = false;
        try {
            MethodEntryRequest request = vm.eventRequestManager().createMethodEntryRequest();
            request.addClassFilter("io.github.jnesew.paudiobooks.metadata.InspectionService");
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            request.enable();
            System.out.println("DEBUGGER_ATTACHED");
            System.out.flush();
            long deadline = System.nanoTime() + 45_000_000_000L;
            while (System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                boolean keepSuspended = false;
                for (Event event : events) {
                    if (event instanceof MethodEntryEvent entry && entry.method().name().equals("inspectDescriptor")) {
                        if (held || entry.thread().name().equals("main")) throw new IllegalStateException("Unexpected inspection thread");
                        held = true;
                        keepSuspended = true;
                        request.disable();
                        System.out.println("INSPECTION_WORKER_HELD thread=" + entry.thread().name());
                        System.out.flush();
                    }
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        if (!held) throw new IllegalStateException("Process died before the worker was held");
                        System.out.println("WATCHDOG_DISCONNECTED");
                        return;
                    }
                }
                if (!keepSuspended) events.resume();
            }
            throw new IllegalStateException("Watchdog did not disconnect within 45 seconds");
        } catch (VMDisconnectedException disconnected) {
            if (!held) throw disconnected;
            System.out.println("WATCHDOG_DISCONNECTED");
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) { }
        }
    }
}
