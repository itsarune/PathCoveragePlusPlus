package pathcoverageplusplus;

import common.PathCoverage;
import common.util.Tuple;
import jit.RuntimeClassLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import runtime.TestExecutor;
import runtime.InstrumentedTestExtension;

import java.io.File;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/paths")
public class PathsController {
    // MOCK INPUT BLOCK START
    Map<String, Map<Integer, Integer>> mockHits = new HashMap<>();
    Map<Integer, Integer> checkA = new HashMap<>();
    Map<Integer, Integer> checkB = new HashMap<>();
    Map<String, ArrayList<ArrayList<Integer>>> mockUncovered = new HashMap<>();
    ArrayList<ArrayList<Integer>> uncoveredPathsA;
    ArrayList<ArrayList<Integer>> uncoveredPathsB;
    ArrayList<Integer> checkAUncovered;
    ArrayList<Integer> checkBUncovered;
    Map<String, Tuple<Integer, Integer>> mockCoverageMeta = new HashMap<>();

    PathCoverage mockInput;

    // MOCK INPUT BLOCK END

    String codefilename = "";
    String testfilename = "";

    @GetMapping
    public ResponseEntity<PathCoverage> getPathValues() {
        // Setting mock input block START

        // Mapping line numbers to number of hits per line
        checkA.put(14, 2);
        checkA.put(15, 3);

        checkB.put(21, 4);
        checkB.put(22, 2);
        checkB.put(23, 2);

        // Mapping Method names to line hits
        mockHits.put("checkA", checkA);
        mockHits.put("checkB", checkB);

        // Mapping uncovered line numbers to the array lists
        checkAUncovered = new ArrayList<>();
        checkAUncovered.add(12);
        checkAUncovered.add(13);

        checkBUncovered = new ArrayList<>();
        checkBUncovered.add(24);
        checkBUncovered.add(25);
        checkBUncovered.add(26);

        uncoveredPathsA = new ArrayList<>();
        uncoveredPathsA.add(checkAUncovered);
        uncoveredPathsB = new ArrayList<>();
        uncoveredPathsB.add(checkBUncovered);

        mockUncovered.put("checkA", uncoveredPathsA);
        mockUncovered.put("checkB", uncoveredPathsB);
        mockCoverageMeta.put("checkA", new Tuple<>(2, 2));
        mockCoverageMeta.put("checkB", new Tuple<>(3, 3));

        // setting mock input
        mockInput = new PathCoverage(
                mockCoverageMeta,
                mockHits,
                mockUncovered
        );

        // Setting mock input block END
        return new ResponseEntity<PathCoverage>(mockInput, HttpStatus.OK);
    }

    @PutMapping
    public ResponseEntity<String> handlePutRequest(@RequestParam("file")MultipartFile file) {
        if (!file.isEmpty()) {
            try {
                // File Putting
                String directory = Optional.ofNullable(System.getProperty("SANDBOX_HOME")).orElseThrow(() -> new IllegalArgumentException("SANDBOX_HOME not set"));
                String filename = file.getOriginalFilename();
                System.out.println(directory + filename);
                file.transferTo(new File(directory + filename));

                // setting filenames
                assert filename != null;
                if (filename.toLowerCase().contains("test")) {
                    testfilename = filename;
                } else {
                    codefilename = filename;
                }

                // determining if we need to execute stuff
                if (!testfilename.isEmpty() && !codefilename.isEmpty()) {
                    handleExecutor();
                }

                // String classname = filename.substring(0, filename.indexOf("."));
                return new ResponseEntity<String>("File and data uploaded successfully", HttpStatus.OK);
            } catch (IOException e) {
                e.printStackTrace();
                return new ResponseEntity<String>("Error uploading file", HttpStatus.BAD_REQUEST);
            }
        } else {
            return new ResponseEntity<String>("File is empty", HttpStatus.NO_CONTENT);
        }
    }

    private void handleExecutor() {
        String[] paths = new String[] {codefilename, testfilename};
        System.out.println(paths[0]);
        try {
            String root = Optional.ofNullable(System.getProperty("SANDBOX_HOME")).orElseThrow(() -> new IllegalArgumentException("SANDBOX_HOME not set"));
            String classname = testfilename.substring(0, testfilename.indexOf("."));
            RuntimeClassLoader classLoader = new RuntimeClassLoader(root, paths);
            Map<String, Class<?>> classes = classLoader.loadClasses();
            TestExecutor testExecutor = new TestExecutor(classes.get(classname));
            testExecutor.runTests();
            PathCoverage pathCoverage = testExecutor.getPathCoverage();
            System.out.println(pathCoverage.getPathCoverageMetadata());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
