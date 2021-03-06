package com.mambu.ant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.security.SecureRandom;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * Ant target for uploading translation files to PhraseApp.
 */
public class PhraseAppUpload extends BaseTask {

	/**
	 * absolute path to the directory where the master translation .properties
	 * files are located
	 */
	protected String sourceDir;

	/**
	 * the long version of the locale from PhraseApp project to upload the
	 * translations from the sourceDir to
	 */
	protected String locale;

	/**
	 * Internal test method to check if the Ant task is working
	 * 
	 * @param args
	 *            ignored
	 * @throws IOException
	 * @throws ProtocolException
	 * @throws MalformedURLException
	 * @throws UnsupportedEncodingException
	 * @throws FileNotFoundException
	 */
	public static void main(String[] args) {
		PhraseAppUpload upload = new PhraseAppUpload();
		upload.setSource("");
		upload.setUserAuthToken("");
		upload.setProjectId("");
		upload.setLocale(DEFAULT_LOCALE_CODE);
		upload.execute();
	}


	/**
	 * Uploads all *.properties files to PhraseApp from the source directory and
	 * tags all translation keys for each uploaded file with the filename
	 */
	public void execute() throws org.apache.tools.ant.BuildException {
		try {

			// configure the SSLContext with another TrustManager to accept all
			// SSL certificates
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(new KeyManager[0],
					new TrustManager[] { new DefaultTrustManager() },
					new SecureRandom());
			SSLContext.setDefault(ctx);

			uploadTranslationPropertiesFiles();

		} catch (Exception e) {
			log("An error occurred: ");
			e.printStackTrace();
			throw new BuildException(e.getMessage());
		}
	}

	public void setSource(String sourceDir) {
		this.sourceDir = sourceDir;
	}

	public void setLocale(String locale) {
		this.locale = locale;
	}

	private void uploadTranslationPropertiesFiles() {

		log("Looking for .properties files in: " + this.sourceDir);

		File dir = new File(this.sourceDir);

		// filter files with the extension ".properties" for the given
		// source directory
		File[] files = dir.listFiles(new FilenameFilter() {

			@Override
			public boolean accept(File dir, String name) {
				return name.contains(".properties");
			}
		});

		// upload each file separately
		if (files != null && files.length != 0) {

			final AtomicInteger failedUploads = new AtomicInteger(0);

			// max 2 parallel connections are allowed by phraseapp, otherwise returns HTTP error code 429
			ExecutorService exec = Executors.newFixedThreadPool(2);

			try {

				for (final File file : files) {
					exec.submit(new Runnable() {
						@Override
						public void run() {
							String prefix = getNamespacePrefix(file);
							log("Uploading file: "
									+ file.getName()
									+ ", prepending '"
									+ prefix
									+ "' to every key to avoid namespace conflicts.");

							try {

								HttpsURLConnection connectionUpload = uploadTranslationPropertiesFile(
										file, prefix);

								log("Sent file upload request for file: "
										+ file.getName()
										+ " to "
										+ PhraseAppHelper.PHRASE_APP_BASE_URL + "/translation_keys/upload/"
										+ ".");

								if (connectionUpload.getResponseCode() == 200) {

									String responseJsonUpload = PhraseAppHelper
											.getAsString(connectionUpload);

									log("File upload request for file: "
											+ file.getName() + " returned: "
											+ responseJsonUpload);

								} else {

									log("First attempt to upload file '"
											+ file.getName()
											+ "' failed, trying again once more.");

									connectionUpload = uploadTranslationPropertiesFile(
											file, prefix);

									if (connectionUpload.getResponseCode() == 200) {
										String responseJsonUpload = PhraseAppHelper
												.getAsString(connectionUpload);
										log(responseJsonUpload);
									} else {
										log("Response code for translation file upload request was: "
												+ connectionUpload
														.getResponseCode()
												+ " ("
												+ connectionUpload
														.getResponseMessage()
												+ ")");
										failedUploads.incrementAndGet();
									}
								}
							} catch (FileNotFoundException e) {
								failedUploads.incrementAndGet();
								log("Could not upload content of file '"
										+ file.getName()
										+ "' due to FileNotFoundException '"
										+ e.getLocalizedMessage() + "'.");
								e.printStackTrace();
							} catch (UnsupportedEncodingException e) {
								failedUploads.incrementAndGet();
								log("Could not upload content of file '"
										+ file.getName()
										+ "' due to UnsupportedEncodingException '"
										+ e.getLocalizedMessage() + "'.");
								e.printStackTrace();
							} catch (MalformedURLException e) {
								failedUploads.incrementAndGet();
								log("Could not upload content of file '"
										+ file.getName()
										+ "' due to UnsupportedEncodingException '"
										+ e.getLocalizedMessage() + "'.");
								e.printStackTrace();
							} catch (ProtocolException e) {
								failedUploads.incrementAndGet();
								log("Could not upload content of file '"
										+ file.getName()
										+ "' due to UnsupportedEncodingException '"
										+ e.getLocalizedMessage() + "'.");
								e.printStackTrace();
							} catch (IOException e) {
								failedUploads.incrementAndGet();
								log("Could not upload content of file '"
										+ file.getName()
										+ "' due to IOException '"
										+ e.getLocalizedMessage() + "'.");
								e.printStackTrace();
							}
						}
					});
				}
			} finally {
				exec.shutdown();
				try {
					if (exec.awaitTermination(10, TimeUnit.MINUTES)) {
						log("Uploading of all .properties files done, "
								+ failedUploads + " uploads totally failed.");
					} else {

					}
				} catch (InterruptedException e) {
					log("Upload of translation files failed due to '"
							+ e.getLocalizedMessage() + "'.");
					e.printStackTrace();
				}
			}

		} else {

			log("No .properties files were found in: " + this.sourceDir + ".");

		}
	}

	public String getNamespacePrefix(File file) {
		String prefix = "";
		String fileName = file.getName();
		String[] fileParts = fileName.split("\\.");
		// e.g. example.properties -> example
		if (fileParts.length == 2) {
			prefix = fileParts[0];
		}
		// e.g. error.jsp.properties -> error
		else if (fileParts.length >= 3
				&& fileParts[fileParts.length - 2].length() <= 4) {
			prefix = fileParts[fileParts.length - 3];
		}
		// e.g. com.mambu.mymambu.email.service.EmailService.properties
		// -> EmailService
		else if (fileParts.length >= 3
				&& fileParts[fileParts.length - 2].length() > 4) {
			prefix = fileParts[fileParts.length - 2];
		}
		// fallback
		else {
			prefix = file.getName().substring(0,
					file.getName().lastIndexOf("."));
		}
		return prefix + ".";
	}

	private HttpsURLConnection uploadTranslationPropertiesFile(File file,
			String prefix) throws FileNotFoundException,
			UnsupportedEncodingException, MalformedURLException, IOException,
			ProtocolException {

		FileInputStream fis = new FileInputStream(file);
		InputStreamReader isr = new InputStreamReader(fis, "UTF-8");
		Scanner scanner = new Scanner(isr);
		Scanner scanner2 = scanner.useDelimiter("\\A");
		String fileContent = scanner2.hasNext() ? scanner2.next() : "";
		scanner2.close();
		scanner.close();
		isr.close();
		fis.close();

		// prepend namespace prefix
		String fileContentWithPrefixes = "";
		for (String line : fileContent.split("\\r?\\n")) {
			if (!line.trim().isEmpty() && !line.startsWith("#")
					&& !(prefix + line).equals(prefix)
					&& !line.trim().endsWith(" =") && !line.trim().equals("=")) {
				fileContentWithPrefixes += prefix + line + "\n";
			}
			// skip adding prefix for comments and empty lines
			else if (line.trim().isEmpty() || line.startsWith("#")) {
				fileContentWithPrefixes += line + "\n";
			}
		}

		String localeCode = locale != null && !locale.isEmpty() ? locale
				: DEFAULT_LOCALE_CODE;

		MultipartUtility multipart = new MultipartUtility(PhraseAppHelper.PHRASE_APP_BASE_URL + projectId + "/uploads",
				"UTF-8");
		multipart.addFormField("access_token", userAuthToken);
		multipart.addFormField("file_format", "properties");
		multipart.addFormField("locale_id", localeCode);
		multipart.addFormField("tags", file.getName());
		multipart.addFormField("update_translations", "true");
		multipart.addFormField("file_encoding", "UTF-8");

		multipart.addFilePartContent("file", file.getName(), fileContentWithPrefixes);

		return multipart.getFinalizedConnection();
	}
}
