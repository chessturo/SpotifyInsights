# SpotifyInsights

SpotifyInsights allows users to view their current top 20 songs.

CSE 143 final project by Mitchell Levy, Jaden Wang, and Lianne Kniest (2020).

## Description

One's music habits are uniquely personal and representative of different moods and preferences - how they change over time is something that when looked at provides a chance to get to know a person better (think Spotify Wrapped). We took inspiration from [Spotify Wrapped](https://2020.byspotify.com/) and wanted to bring the deeper access to a user's data that Spotify allows but doesn't make easily accessible.

This app has a great framework set up that allows for quick and easy data interpretation of Spotify information in the future and allows easy access to Spotify API data as a platform.

Our tool aims to show insight on a user's music taste and show their current favorite tracks.
The next goal for the project if continued is to index the most frequent genre in a user's "Liked Songs" library and see how that has changed over time. 

## Project Video

See a quick overview of our project and some discussion on it 

[![Video thumbnail](http://img.youtube.com/vi/n2M35vqMEWI/0.jpg)](http://www.youtube.com/watch?v=n2M35vqMEWI "SpotifyInsights Video"). 

We discuss our overall aims and our development process with some highlights on what we worked on most.

# How to Access/Install

## Web Access

Visit [SpotifyInsights](http://spotifyinsights.app).

Login to Spotify and allow SpotifyInsights access. No need to download any files locally. A valid Spotify account and login information is needed.

## Local Installation/Run

#### Getting the Files Using Git
1. Create a new directory where you want your copy of SpotifyInsights to be downloaded to.
    > Note: a new folder containing the repo with the name of the repository will be created in the directory you're in
2. Verify that Git is installed by opening Command Prompt/Terminal (Windows vs. Linux) and typing in `git version`
3. Using either Git Bash/Terminal (Windows vs Linux), `cd` to the directory that you made and clone the repo to your local using `git clone -depth=1 https://github.com/levymitchell0/SpotifyInsights.git`
    > This clone command creates a shallow clone (one with only the most recent commit)
4. Follow "Running the Program" below

#### Getting the Files Using a ZIP File

1. [Here](https://github.com/levymitchell0/SpotifyInsights), click on the "code tab" and click download as ZIP.
2. Extract the files to a desired location
3. Follow "Running the Program" below

#### Running the Program 
1. Import the files into an IDE of your choice (our preference is Eclipse)
2. Build and run the Server.java file
3. Go to a web browser and visit `localhost/login`

## Contributing

As this is a final project for a class assignment, SpotifyInsights is not open to contributions. Its status is complete-as-is.
