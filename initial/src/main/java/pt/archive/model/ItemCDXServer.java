package pt.archive.model;

public class ItemCDXServer {
	private String url;
	private String timestamp;
	private String digest;
	
	public ItemCDXServer(String url, String timestamp, String digest) {
		super();
		this.url = url;
		this.timestamp = timestamp;
		this.digest = digest;
	}
	
	public String getUrl() {
		return url;
	}
	public void setUrl(String url) {
		this.url = url;
	}
	public String getTimestamp() {
		return timestamp;
	}
	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}
	public String getDigest() {
		return digest;
	}
	public void setDigest(String digest) {
		this.digest = digest;
	}

	@Override
	public String toString() {
		return "ItemCDXServer [url=" + url + ", timestamp=" + timestamp + ", digest=" + digest + "]";
	}
	
	
}
