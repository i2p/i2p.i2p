/*
 * Created on May 21, 2005
 */
package net.sf.launch4j.config;

import net.sf.launch4j.binding.IValidatable;
import net.sf.launch4j.binding.Validator;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class VersionInfo implements IValidatable {
	public static final String VERSION_PATTERN = "(\\d+\\.){3}\\d+";
	private static final int MAX_LEN = 150;

	private String fileVersion;
	private String txtFileVersion;
	private String fileDescription;
	private String copyright;
	private String productVersion;
	private String txtProductVersion;
	private String productName;
	private String companyName;
	private String internalName;
	private String originalFilename;

	public void checkInvariants() {
		Validator.checkString(fileVersion, 20, VERSION_PATTERN,
				"versionInfo.fileVersion", "File version, should be 'x.x.x.x'");
		Validator.checkString(txtFileVersion, 50,
				"versionInfo.txtFileVersion", "Free form file version");
		Validator.checkString(fileDescription, MAX_LEN,
				"versionInfo.fileDescription", "File description");
		Validator.checkString(copyright, MAX_LEN,
				"versionInfo.copyright", "Copyright");
		Validator.checkString(productVersion, 20, VERSION_PATTERN,
				"versionInfo.productVersion", "Product version, should be 'x.x.x.x'");
		Validator.checkString(txtProductVersion, 50,
				"versionInfo.txtProductVersion", "Free from product version");
		Validator.checkString(productName, MAX_LEN,
				"versionInfo.productName", "Product name");
		Validator.checkOptString(companyName, MAX_LEN,
				"versionInfo.companyName", "Company name");
		Validator.checkString(internalName, 50,
				"versionInfo.internalName", "Internal name");
		Validator.checkTrue(!internalName.endsWith(".exe"),
				"versionInfo.internalName",
				"Internal name shouldn't have the .exe extension.");
		Validator.checkString(originalFilename, 50,
				"versionInfo.originalFilename", "Original filename");
		Validator.checkTrue(originalFilename.endsWith(".exe"),
				"versionInfo.originalFilename",
				"Original filename should end with the .exe extension.");
	}

	public String getCompanyName() {
		return companyName;
	}

	public void setCompanyName(String companyName) {
		this.companyName = companyName;
	}

	public String getCopyright() {
		return copyright;
	}

	public void setCopyright(String copyright) {
		this.copyright = copyright;
	}

	public String getFileDescription() {
		return fileDescription;
	}

	public void setFileDescription(String fileDescription) {
		this.fileDescription = fileDescription;
	}

	public String getFileVersion() {
		return fileVersion;
	}

	public void setFileVersion(String fileVersion) {
		this.fileVersion = fileVersion;
	}

	public String getInternalName() {
		return internalName;
	}

	public void setInternalName(String internalName) {
		this.internalName = internalName;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public void setOriginalFilename(String originalFilename) {
		this.originalFilename = originalFilename;
	}

	public String getProductName() {
		return productName;
	}

	public void setProductName(String productName) {
		this.productName = productName;
	}

	public String getProductVersion() {
		return productVersion;
	}

	public void setProductVersion(String productVersion) {
		this.productVersion = productVersion;
	}

	public String getTxtFileVersion() {
		return txtFileVersion;
	}

	public void setTxtFileVersion(String txtFileVersion) {
		this.txtFileVersion = txtFileVersion;
	}

	public String getTxtProductVersion() {
		return txtProductVersion;
	}

	public void setTxtProductVersion(String txtProductVersion) {
		this.txtProductVersion = txtProductVersion;
	}
}
