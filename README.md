# UrlLoader
Android Http Download Library



## Dependency


**Step 1.** Add the JitPack repository to your build file 

Add it in your root build.gradle at the end of repositories:

```
	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```

**Step 2.** Add the dependency

```
	dependencies {
	        implementation 'com.github.shxhzhxx:UrlLoader:1.1.0'
	}
```


## Features

- Simple API
- Partial download base on Http protocol
- Max disk cache control
- Combine identical download to save bandwidth



## Usage

```
//init once. typically in Application's onCreate callback
UrlLoader.init(getCacheDir());


String url="http://xxx";
UrlLoader.load(url, new UrlLoader.ProgressObserver() {
    @Override
    public void onComplete(File file) {
    	//success
    }
});
```

