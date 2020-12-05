import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

//The Track class stores information of a Spotify track. 
public class Track {
	private String title;
	private List<String> artists;

	// post-condition: Constructs a new Track object.
	public Track() {
		this.title = null;
		this.artists = null;
	}

	// pre-condition : Takes in a String representing the title of the track
	//                 and a List<String> representing the artists of the track.
	// post-condition: Constructs a new Track object.
	public Track(String title, List<String> artists) {
		this.title = title;
		this.artists = artists;
	}

	// pre-condition : Takes in a String of the JSON representation of a track.
	// post-condition: Constructs a new Track object.
	public Track(String json) {
		JSONObject obj = new JSONObject(json);
		
		this.title = obj.getString("name");
		
		List<Object> artistList = obj.getJSONArray("artists").toList();	
		this.artists = new LinkedList<>();
		for (int artist = 0; artist < artistList.size(); artist++) {
			Map<String, String> map = (HashMap<String, String>) artistList.get(artist);
			this.artists.add(map.get("name")); 
		}
	}
	
	// pre-condition : Takes in a String of the JSON representation of the tracks.
	// post-condition: Returns List<Track> of the inputted tracks.
	public static List<Track> fromJsonArray(String json) {
		JSONArray jsonTracks = new JSONArray(json);
		List<Track> tracks = new LinkedList<>();
		for (int t = 0; t < jsonTracks.length(); t++) {
			Track current = new Track(jsonTracks.get(t).toString());
			tracks.add(current);
		}
		return tracks;
	}

	// post-condition: Returns String of the title of the track.
	public String getTitle() {
		return title;
	}

	// post-condition: Returns List<String> of the artists of the track.
	public List<String> getArtists() {
		return artists;
	}

	// post-condition: Returns String representation of Track.
	public String toString() {
		return title + " " + artists.toString();
	}
}