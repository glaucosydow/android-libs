package com.zehfernando.net.apis.leverage.data;

import com.zehfernando.data.xml.XML;
import com.zehfernando.net.apis.leverage.LeverageConstants;
import com.zehfernando.net.apis.leverage.enums.LeverageObjectTypes;

public class LeverageGroup extends LeverageObject {

	// Properties
	protected String imageURL;
	protected String authorId;
	protected String authorName;

	// ================================================================================================================
	// CONSTRUCTOR ----------------------------------------------------------------------------------------------------

	public LeverageGroup() {
		super();

		type = LeverageObjectTypes.GROUP;

		imageURL = "";
		authorId = "";
		authorName = "";
	}

	// ================================================================================================================
	// PUBLIC INTERFACE -----------------------------------------------------------------------------------------------

	@Override
	public void setDataFromXML(XML __item) {
		super.setDataFromXML(__item);

		imageURL = __item.getChild("enclosure", "").getAttribute("url").getText() + LeverageConstants.THUMBNAIL_PREFFIX;
		authorId = __item.getChild("OwnerID", "").getText();
		authorName = __item.getChild("MemberName", "").getText();

		// ls:CustomerID // Same as owner ID
		// ls:PrivacySetting
	}

	// ================================================================================================================
	// ACCESSOR INTERFACE ---------------------------------------------------------------------------------------------

	public String getImageURL() {
		return imageURL.replace("&w=50","&w=100").replace("&h=50","&h=100");
	}

	public String authorId() {
		return authorId;
	}

	public String getAuthorName() {
		return authorName;
	}
}
