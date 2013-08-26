/*
 * Copyright (c) 2013 Mozilla Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package nu.validator.client;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import nu.validator.htmlparser.sax.XmlSerializer;
import nu.validator.messages.GnuMessageEmitter;
import nu.validator.messages.JsonMessageEmitter;
import nu.validator.messages.MessageEmitterAdapter;
import nu.validator.messages.TextMessageEmitter;
import nu.validator.messages.XmlMessageEmitter;
import nu.validator.servlet.imagereview.ImageCollector;
import nu.validator.source.SourceCode;
import nu.validator.validation.SimpleValidator;
import nu.validator.validation.SimpleValidator.DocParseException;
import nu.validator.validation.SimpleValidator.SchemaReadException;
import nu.validator.xml.SystemErrErrorHandler;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * 
 * Simple command-line validator for HTML/XHTML files.
 */
public class SimpleCommandLineValidator {

    private static SimpleValidator validator;

    private static OutputStream out;

    private static MessageEmitterAdapter errorHandler;

    private static boolean verbose;

    private static boolean errorsOnly;

    private static boolean loadEntities;

    private static boolean forceHTML;

    private static boolean asciiQuotes;

    private static int lineOffset;

    private static enum OutputFormat {
        HTML, XHTML, TEXT, XML, JSON, RELAXED, SOAP, UNICORN, GNU
    }

    private static OutputFormat outputFormat;

    public static void main(String[] args) throws SAXException, Exception {
        out = System.err;
        errorsOnly = false;
        forceHTML = false;
        loadEntities = false;
        lineOffset = 0;
        asciiQuotes = false;
        verbose = false;

        String outFormat = null;
        String schemaUrl = null;
        boolean hasFileArgs = false;
        int fileArgsStart = 0;
        if (args.length == 0) {
            usage();
            System.exit(-1);
        }
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                hasFileArgs = true;
                fileArgsStart = i;
                break;
            } else {
                if ("--verbose".equals(args[i])) {
                    verbose = true;
                } else if ("--format".equals(args[i])) {
                    outFormat = args[++i];
                } else if ("--html".equals(args[i])) {
                    forceHTML = true;
                } else if ("--entities".equals(args[i])) {
                    loadEntities = true;
                } else if ("--schema".equals(args[i])) {
                    schemaUrl = args[++i];
                    if (!schemaUrl.startsWith("http:")) {
                        System.err.println("error: The \"--schema\" option"
                                + " requires a URL for a schema.");
                        System.exit(-1);
                    }
                }
            }
        }
        if (schemaUrl == null) {
            schemaUrl = "http://s.validator.nu/html5-all.rnc";
        }
        if (outFormat == null) {
            outputFormat = OutputFormat.GNU;
        } else {
            if ("text".equals(outFormat)) {
                outputFormat = OutputFormat.TEXT;
            } else if ("gnu".equals(outFormat)) {
                outputFormat = OutputFormat.GNU;
            } else if ("xml".equals(outFormat)) {
                outputFormat = OutputFormat.XML;
            } else if ("json".equals(outFormat)) {
                outputFormat = OutputFormat.JSON;
            } else {
                System.err.printf("Error: Unsupported output format \"%s\"."
                        + " Must be \"gnu\", \"xml\", \"json\","
                        + " or \"text\".\n", outFormat);
                System.exit(-1);
            }
        }
        if (hasFileArgs) {
            List<File> files = new ArrayList<File>();
            for (int i = fileArgsStart; i < args.length; i++) {
                files.add(new File(args[i]));
            }
            validator = new SimpleValidator();
            setErrorHandler();
            validateFilesAgainstSchema(files, schemaUrl);
        } else {
            System.err.printf("\nError: No documents specified.\n");
            usage();
            System.exit(-1);
        }
    }

    private static void validateFilesAgainstSchema(List<File> files,
            String schemaUrl) throws SAXException, Exception {
        try {
            validator.setUpMainSchema(schemaUrl, new SystemErrErrorHandler());
        } catch (SchemaReadException e) {
            System.out.println(e.getMessage() + " Terminating.");
            System.exit(-1);
        }
        validator.setUpValidatorAndParsers(errorHandler, loadEntities);
        errorHandler.start(null);
        checkFiles(files);
        errorHandler.end("Document checking completed. No errors found.",
                "Document checking completed.");
    }

    private static void checkFiles(List<File> files) throws SAXException,
            IOException {
        for (File file : files) {
            if (file.isDirectory()) {
                recurseDirectory(file);
            } else {
                checkHtmlFile(file);
            }
        }
    }

    private static void recurseDirectory(File directory) throws SAXException,
            IOException {
        File[] files = directory.listFiles();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file.isDirectory()) {
                recurseDirectory(file);
            } else {
                checkHtmlFile(file);
            }
        }
    }

    private static void checkHtmlFile(File file) throws IOException,
            SAXException {
        String path = file.getPath();
        if (path.matches("^http:/[^/].+$")) {
            path = "http://" + path.substring(path.indexOf("/") + 1);
            emitFilename(path);
            try {
                validator.checkHttpURL(new URL(path));
            } catch (IOException e) {
                errorHandler.error(new SAXParseException(e.toString(), null,
                        path, -1, -1));
            } catch (DocParseException e) {
                endDueToFatalError(path);
            }
        } else if (path.matches("^https:/[^/].+$")) {
            path = "https://" + path.substring(path.indexOf("/") + 1);
            emitFilename(path);
            try {
                validator.checkHttpURL(new URL(path));
            } catch (IOException e) {
                errorHandler.error(new SAXParseException(e.toString(), null,
                        path, -1, -1));
            } catch (DocParseException e) {
                endDueToFatalError(path);
            }
        } else if (!file.exists()) {
            if (verbose) {
                errorHandler.warning(new SAXParseException("File not found.",
                        null, file.toURI().toURL().toString(), -1, -1));
            }
            return;
        } else if (isHtml(file)) {
            emitFilename(path);
            try {
                validator.checkHtmlFile(file, true);
            } catch (DocParseException e) {
                endDueToFatalError(path);
            }
        } else if (isXhtml(file)) {
            emitFilename(path);
            if (forceHTML) {
                try {
                    validator.checkHtmlFile(file, true);
                } catch (DocParseException e) {
                    endDueToFatalError(path);
                }
            } else {
                try {
                    validator.checkXmlFile(file);
                } catch (DocParseException e) {
                    endDueToFatalError(path);
                }
            }
        } else {
            if (verbose) {
                errorHandler.warning(new SAXParseException(
                        "File was not checked. Files must have a .html,"
                                + " .xhtml, .htm, or .xht extension.", null,
                        file.toURI().toURL().toString(), -1, -1));
            }
        }
    }

    private static boolean isXhtml(File file) {
        String name = file.getName();
        return (name.endsWith(".xhtml") || name.endsWith(".xht"));
    }

    private static boolean isHtml(File file) {
        String name = file.getName();
        return (name.endsWith(".html") || name.endsWith(".htm"));
    }

    private static void emitFilename(String name) {
        if (verbose) {
            System.out.println(name);
        }
    }

    private static void setErrorHandler() {
        SourceCode sourceCode = new SourceCode();
        ImageCollector imageCollector = new ImageCollector(sourceCode);
        boolean showSource = false;
        if (outputFormat == OutputFormat.TEXT) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, new TextMessageEmitter(out,
                            asciiQuotes));
        } else if (outputFormat == OutputFormat.GNU) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, new GnuMessageEmitter(out,
                            asciiQuotes));
        } else if (outputFormat == OutputFormat.XML) {
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, new XmlMessageEmitter(
                            new XmlSerializer(out)));
        } else if (outputFormat == OutputFormat.JSON) {
            String callback = null;
            errorHandler = new MessageEmitterAdapter(sourceCode, showSource,
                    imageCollector, lineOffset, new JsonMessageEmitter(
                            new nu.validator.json.Serializer(out), callback));
        } else {
            throw new RuntimeException("Bug. Should be unreachable.");
        }
        errorHandler.setErrorsOnly(errorsOnly);
    }

    private static void endDueToFatalError(String documentName)
            throws SAXException, IOException {
        // If we end up here it's almost certainly because either the HTML
        // parser or XML parser ran into a fatal parse error. And for error
        // handling we're using the MessageEmitter mechanism, and the
        // MessageEmitter code is basically built around the use case of
        // validating only one document at a time (rather than doing batch
        // validation of multiple document). So once the MessageEmitter hits a
        // fatal error in one document, it sorta assumes its job is done, and
        // becomes usable for reporting errors from any more documents. So the
        // only thing we can do at this point is to end and emit a message
        // saying there was a fatal error, along with the name of the document
        // that had the fatal error (hopefully the right name, though
        // unfortunately there may be some cases where it might not be...)
        errorHandler.end("", "");
        System.out.print(String.format("%s"
                + "\n\nDocument checking stopped prematurely."
                + "\nThere was a fatal error."
                + " Fix the error and recheck your document(s).\n",
                documentName));
    }

    private static void usage() {
        System.out.println("");
        System.out.println("Usage:");
        System.out.println("");
        System.out.println("  java -jar vnu.jar [--verbose] [--html]");
        System.out.println("      [--format gnu|xml|json|text]");
        System.out.println("      [--entities] [--schema URL] FILES");
        System.out.println("");
        System.out.println("To validate one or more documents from the command line:");
        System.out.println("");
        System.out.println("  java -jar vnu.jar FILE.html FILE2.html FILE3.HTML FILE4.html...");
        System.out.println("");
        System.out.println("To validate all HTML documents in a particular directory:");
        System.out.println("");
        System.out.println("  java -jar vnu.jar some-directory-name/");
        System.out.println("");
        System.out.println("To validate a Web document:");
        System.out.println("");
        System.out.println("  java -jar vnu.jar http://example.com/foo");
        System.out.println("");
        System.out.println("Other usage:");
        System.out.println("");
        System.out.println("To start the validator as an HTTP service on port 8888:");
        System.out.println("");
        System.out.println("  java -cp vnu.jar nu.validator.servlet.Main 8888");
        System.out.println("");
        System.out.println("To validate one or more documents with a running instance of the validator HTTP service:");
        System.out.println("");
        System.out.println("  java -cp vnu.jar nu.validator.client.HttpClient FILE.html FILE2.html...");
    }
}
