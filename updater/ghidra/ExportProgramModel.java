// Exports the raw program model the kraken native updater imports into SQLite (updater/src/native_updater/model.py).
//
//   analyzeHeadless <projDir> <projName> -import osclient.exe -scriptPath updater/ghidra \
//       -postScript ExportProgramModel.java <outDir>
//
// Writes three files to <outDir>; every address is an RVA in lowercase hex without "0x":
//   meta.tsv          key \t value
//   functions.tsv.gz  entry \t name \t is_thunk \t thunk_target \t body ranges "start-end,..." (end exclusive)
//   insns.tsv.gz      rva \t function entry or "-" \t length \t bytes hex \t text \t flows "a,b" \t fallthrough
//
// Everything else (sections, strings, .pdata, data pointers) is read from the PE file by Python.
// @category Kraken

import ghidra.app.script.GhidraScript;
import ghidra.framework.Application;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressRange;
import ghidra.program.model.listing.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class ExportProgramModel extends GhidraScript {

    private long base;

    @Override
    public void run() throws Exception {
        File out = new File(getScriptArgs()[0]);
        out.mkdirs();
        base = currentProgram.getImageBase().getOffset();

        int created = createMissingFunctions();
        int functions = writeFunctions(new File(out, "functions.tsv.gz"));
        int insns = writeInstructions(new File(out, "insns.tsv.gz"));

        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(out, "meta.tsv")), "UTF-8"))) {
            w.println("ghidra_version\t" + Application.getApplicationVersion());
            w.println("image_base\t" + Long.toHexString(base));
            w.println("sha256\t" + currentProgram.getExecutableSHA256());
            w.println("functions\t" + functions);
            w.println("functions_created\t" + created);
            w.println("insns\t" + insns);
        }
        println("Exported " + functions + " functions (" + created + " created) and " + insns + " instructions to " + out);
    }

    // Ghidra disassembles some code it never puts in a function, mostly sol2 lambdas that are only
    // referenced by a LEA. Give each such run of code a function so every instruction has an owner.
    private int createMissingFunctions() {
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        List<Address> starts = new ArrayList<>();
        Instruction prev = null;
        for (Instruction ins : listing.getInstructions(true)) {
            if (fm.getFunctionContaining(ins.getAddress()) == null) {
                boolean continues = prev != null && fm.getFunctionContaining(prev.getAddress()) == null
                        && ins.getAddress().equals(prev.getFallThrough());
                if (!continues) {
                    starts.add(ins.getAddress());
                }
            }
            prev = ins;
        }
        int created = 0;
        for (Address start : starts) {
            if (fm.getFunctionContaining(start) == null && createFunction(start, null) != null) {
                created++;
            }
        }
        return created;
    }

    private int writeFunctions(File file) throws IOException {
        int count = 0;
        try (PrintWriter w = gzipWriter(file)) {
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                String thunk = "";
                if (f.isThunk()) {
                    Function target = f.getThunkedFunction(false);
                    if (target != null && target.getEntryPoint().isMemoryAddress()) {
                        thunk = rva(target.getEntryPoint());
                    }
                }
                StringBuilder body = new StringBuilder();
                for (AddressRange r : f.getBody()) {
                    if (body.length() > 0) {
                        body.append(',');
                    }
                    body.append(rva(r.getMinAddress())).append('-').append(Long.toHexString(r.getMaxAddress().getOffset() + 1 - base));
                }
                w.println(rva(f.getEntryPoint()) + "\t" + f.getName() + "\t" + (f.isThunk() ? 1 : 0) + "\t" + thunk + "\t" + body);
                count++;
            }
        }
        return count;
    }

    private int writeInstructions(File file) throws IOException, Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        int count = 0;
        try (PrintWriter w = gzipWriter(file)) {
            for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
                if (monitor.isCancelled()) {
                    break;
                }
                Function f = fm.getFunctionContaining(ins.getAddress());
                StringBuilder flows = new StringBuilder();
                for (Address flow : ins.getFlows()) {
                    if (flow.isMemoryAddress()) {
                        if (flows.length() > 0) {
                            flows.append(',');
                        }
                        flows.append(rva(flow));
                    }
                }
                Address fallthrough = ins.getFallThrough();
                w.print(rva(ins.getAddress()));
                w.print('\t');
                w.print(f == null ? "-" : rva(f.getEntryPoint()));
                w.print('\t');
                w.print(ins.getLength());
                w.print('\t');
                w.print(hex(ins.getBytes()));
                w.print('\t');
                w.print(ins.toString());
                w.print('\t');
                w.print(flows);
                w.print('\t');
                w.println(fallthrough == null ? "" : rva(fallthrough));
                count++;
            }
        }
        return count;
    }

    private String rva(Address a) {
        return Long.toHexString(a.getOffset() - base);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static PrintWriter gzipWriter(File file) throws IOException {
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(file), 1 << 16), "UTF-8"), 1 << 20));
    }
}
