package com.logicaldoc.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.Version;
import com.logicaldoc.core.document.dao.DocumentDAO;
import com.logicaldoc.core.document.dao.VersionDAO;
import com.logicaldoc.core.store.Storer;

import com.logicaldoc.core.security.Session;
import com.logicaldoc.util.Context;
import com.logicaldoc.web.util.ServletUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

import com.logicaldoc.util.io.FileUtil;
/**
 * This servlet simply download the document it is a PDF.
 * 
 * @author Marco Meschieri - LogicalDOC
 * @since 7.4.2
 */
public class ConvertPdf extends HttpServlet {

	private static final String VERSION = "version";

	private static final String DOCUMENT_ID = "docId";

	private static final long serialVersionUID = 1L;

	protected static Logger log = LoggerFactory.getLogger(ConvertPdf.class);

	public static final String PDF_CONVERSION_SUFFIX = "conversion.pdf";

	/**
	 * Constructor of the object.
	 */
	public ConvertPdf() {
		super();
	}

	/**
	 * The doGet method of the servlet. <br>
	 * 
	 * This method is called when a form has its tag value method equals to get.
	 * 
	 * @param request the request send by the client to the server
	 * @param response the response send by the server to the client
	 * 
	 * @throws ServletException if an error occurred
	 * @throws IOException if an error occurred
	 */
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Session session = ServletUtil.validateSession(request);

		DocumentDAO docDao = (DocumentDAO) Context.get().getBean(DocumentDAO.class);
		VersionDAO versionDao = (VersionDAO) Context.get().getBean(VersionDAO.class);
		Storer storer = (Storer) Context.get().getBean(Storer.class);

		try {
			long documentId = Long.parseLong(request.getParameter(DOCUMENT_ID));
			Document document = docDao.findById(documentId);
			if (document.getDocRef() != null) {
				document = docDao.findById(document.getDocRef());
			}

			String versionCode = StringUtils.defaultIfEmpty(request.getParameter(VERSION), document.getVersion());
			Version version = versionDao.findByVersion(document.getId(), versionCode);
			String resourceOrig = storer.getResourceName(document, version.getFileVersion(), null);
			String resourceName = storer.getResourceName(document, version.getFileVersion(), PDF_CONVERSION_SUFFIX);
			boolean resourceExists = storer.exists(document.getId(), resourceName);
			boolean isPdf = document.getFileName().toLowerCase().endsWith(".pdf");

			File tempInput = null;
			File tempOutput = null;

			if (!resourceExists && !document.getFileName().toLowerCase().endsWith(".pdf")) {
				String fileName = document.getFileName().toLowerCase();

				if (fileName.endsWith(".doc") || fileName.endsWith(".docx") ||
					fileName.endsWith(".odt") || fileName.endsWith(".xls") ||
					fileName.endsWith(".xlsx") || fileName.endsWith(".ppt") ||
					fileName.endsWith(".pptx")) {

					try {
						tempInput = File.createTempFile("res-", ".cache");
						storer.writeToFile(document.getId(), resourceOrig, tempInput);

						tempOutput = File.createTempFile("res-", ".pdf");
						String outputDir = tempOutput.getParent();

						ProcessBuilder pb = new ProcessBuilder(
								"/usr/bin/libreoffice",
								"--headless",
								"--convert-to", "pdf",
								tempInput.getAbsolutePath(),
								"--outdir", outputDir
						);

						pb.redirectErrorStream(true);
						Process process = pb.start();
						int exitCode = process.waitFor();

						File generatedPdf = new File(outputDir, tempInput.getName().replaceFirst("\\.cache$", ".pdf"));

						if (exitCode == 0 && generatedPdf.exists()) {
							storer.store(generatedPdf, document.getId(), resourceName);
						} else {
							try (InputStream is = ConvertPdf.class.getResourceAsStream("/pdf/notavailable.pdf")) {
								if (is == null) throw new Exception("Failed to find notavailable.pdf.");
								storer.store(is, document.getId(), resourceName);
							}
						}

					} catch (Exception e) {
						throw new Exception("Failed generate preview.", e);
					} finally {
						if (tempInput != null) FileUtil.strongDelete(tempInput);
						if (tempOutput != null) FileUtil.strongDelete(tempOutput);
					}
				}
			}


			if (resourceExists) {
				ServletUtil.downloadDocument(request, response, null, document.getId(), version.getFileVersion(), null, PDF_CONVERSION_SUFFIX, session.getUser());
			} else {
				if (!isPdf) {
					throw new Exception("Unsupported format: only PDF files are supported for this operation.");
				}
				ServletUtil.downloadDocument(request, response, null, document.getId(), version.getFileVersion(), null, null, session.getUser());
			}

		} catch (Throwable r) {
			log.error(r.getMessage(), r);

			ServletUtil.setContentDisposition(request, response, "notavailable.pdf");

			InputStream is = ConvertPdf.class.getResourceAsStream("/pdf/notavailable.pdf");
			OutputStream os;
			os = response.getOutputStream();
			int letter = 0;

			try {
				while ((letter = is.read()) != -1)
					os.write(letter);
			} finally {
				os.flush();
				os.close();
				is.close();
			}
		}
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doGet(request, response);
	}
}